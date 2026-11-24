package com.teamplespace.presentation.service;

import static com.teamplespace.composition.service.OutlineInput.invalid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.collaboration.service.ProjectEvents;
import com.teamplespace.composition.service.DesignService;
import com.teamplespace.composition.service.OutlineInput;
import com.teamplespace.composition.service.OutlineService;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;
import com.teamplespace.presentation.infra.OpenAiPresentationClient;
import com.teamplespace.presentation.service.SlideLayouts.Deck;
import com.teamplespace.presentation.service.SlideLayouts.Point;
import com.teamplespace.presentation.service.SlideLayouts.Section;
import com.teamplespace.presentation.service.SlideLayouts.Slide;
import com.teamplespace.presentation.service.SlideLayouts.Theme;
import com.teamplespace.processing.service.JobFailure;
import com.teamplespace.processing.service.JobHandler;
import com.teamplespace.processing.service.JobQueue;
import com.teamplespace.workspace.service.ProjectAccess;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 저장한 구성과 디자인, 선택한 자료로 첫 발표 문서를 만든다. 이미 문서가 있으면 만들지 않는다
@Service
public class PresentationGeneration implements JobHandler {

	public record Registration(int status, JsonNode body) {
	}

	record Source(UUID materialId, long materialVersion, UUID revisionId, long sourceVersion) {
	}

	// contracts의 PresentationGenerationSnapshot
	record Snapshot(int schemaVersion, OutlineService.Outline outline, DesignService.Settings designSettings,
			List<Source> sources) {
	}

	record Input(Snapshot snapshot, String model, String promptVersion) {
	}

	private record Evidence(Source reference, String title, String text) {
	}

	static final String TYPE = "presentation_generation";
	private static final int SCHEMA_VERSION = 1;
	private static final String PROMPT_VERSION = "presentation-v2";
	private static final Set<String> REQUEST_FIELDS = Set.of("outlineVersion", "designVersion", "sourceIds");
	private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
	private static final String FONT = "Pretendard";
	private static final int SOURCE_TITLE_MAX = 18;
	private static final int SOURCE_TITLES_SHOWN = 3;

	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final ProjectAccess access;
	private final OutlineService outlines;
	private final DesignService designs;
	private final JobQueue jobs;
	private final ProjectEvents events;
	private final IdempotencyStore idempotency;
	private final OpenAiPresentationClient provider;

	public PresentationGeneration(JdbcClient jdbc, ObjectMapper json, ProjectAccess access, OutlineService outlines,
			DesignService designs, JobQueue jobs, ProjectEvents events, IdempotencyStore idempotency,
			OpenAiPresentationClient provider) {
		this.jdbc = jdbc;
		this.json = json;
		this.access = access;
		this.outlines = outlines;
		this.designs = designs;
		this.jobs = jobs;
		this.events = events;
		this.idempotency = idempotency;
		this.provider = provider;
	}

	@Transactional
	public Registration create(UUID actor, UUID projectId, String keyValue, JsonNode body) {
		access.lock(projectId);
		access.requireMember(projectId, actor);
		if (keyValue == null || keyValue.isBlank() || keyValue.length() > 128) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		var key = IdempotencyStore.Key.of(actor, "POST", "/v1/projects/" + projectId + "/presentation-jobs", keyValue);
		var stored = idempotency.findOrClaim(key, CanonicalJson.sha256(body));
		if (stored.isPresent()) {
			return new Registration(stored.get().status(), json.readTree(stored.get().body()));
		}
		if (!provider.available()) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		if (presentationExists(projectId)) {
			throw new ApiException(ErrorCode.REGENERATION_NOT_ENABLED);
		}
		OutlineInput.checkFields(body, REQUEST_FIELDS, "presentationJob");
		long outlineVersion = OutlineInput.version(body.path("outlineVersion"), "outlineVersion");
		long designVersion = OutlineInput.version(body.path("designVersion"), "designVersion");
		OutlineService.Outline outline = outlines.get(actor, projectId);
		DesignService.Settings design = designs.get(actor, projectId);
		if (outline.version() != outlineVersion || design.version() != designVersion) {
			var current = json.createObjectNode().put("outlineVersion", outline.version())
				.put("designVersion", design.version());
			return complete(key, 409, current, projectId);
		}
		if (outline.items().isEmpty()) {
			throw invalid("outlineVersion", "발표 구성을 먼저 만들어 주세요.");
		}
		if (!Set.of("template", "custom").contains(design.mode())) {
			throw invalid("designVersion", "디자인을 먼저 정해 주세요.");
		}

		List<UUID> ids = OutlineInput.sources(body.path("sourceIds"), "sourceIds");
		if (ids.isEmpty()) {
			throw invalid("sourceIds", "발표에 사용할 자료를 하나 이상 선택해 주세요.");
		}
		List<Source> sources = readEvidence(projectId, ids, false).stream().map(Evidence::reference).toList();
		if (sources.size() != ids.size()) {
			throw invalid("sourceIds", "같은 프로젝트에서 이용할 수 있는 자료를 선택해 주세요.");
		}
		// 구성의 근거가 예전 원문이면 최신 원문으로 바꾸지 않고 구성을 확인하게 한다
		Map<UUID, UUID> revisions = new HashMap<>();
		sources.forEach(source -> revisions.put(source.materialId(), source.revisionId()));
		for (OutlineService.Item item : outline.items()) {
			for (OutlineService.SourceRef ref : item.sourceRefs()) {
				if (ref.sourceDeleted() || !ref.revisionId().equals(revisions.get(ref.materialId()))) {
					throw invalid("sourceIds", "구성에 연결한 자료를 모두 선택하고 바뀐 근거는 구성에서 다시 확인해 주세요.");
				}
			}
		}
		boolean alreadyRunning = jdbc.sql("""
				SELECT 1 FROM jobs WHERE project_id = UUID_TO_BIN(?) AND type = ?
				  AND status IN ('queued', 'running') LIMIT 1
				""").params(projectId.toString(), TYPE).query(Integer.class).optional().isPresent();
		if (alreadyRunning) {
			throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS, IdempotencyStore.RETRY_AFTER_SECONDS);
		}

		Input input = new Input(new Snapshot(SCHEMA_VERSION, outline, design, sources), provider.model(), PROMPT_VERSION);
		ObjectNode snapshot = json.createObjectNode();
		snapshot.set("input", json.valueToTree(input));
		snapshot.putObject("inputVersions").put("outlineVersion", outlineVersion).put("designVersion", designVersion);
		UUID jobId = jobs.register(projectId, actor, TYPE, "presentation", projectId, snapshot);
		return complete(key, 202, json.valueToTree(jobs.get(actor, jobId)), jobId);
	}

	private Registration complete(IdempotencyStore.Key key, int status, JsonNode body, UUID resource) {
		idempotency.complete(key, status == 202, status, json.writeValueAsString(body), resource);
		return new Registration(status, body);
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Object execute(JobQueue.Job job) {
		Input input = input(job);
		List<Evidence> evidence = current(job, input, true);
		OutlineService.Outline outline = input.snapshot().outline();
		Map<UUID, Integer> numbers = new HashMap<>();
		for (int index = 0; index < evidence.size(); index++) {
			numbers.put(evidence.get(index).reference().materialId(), index + 1);
		}

		var prompt = json.createObjectNode();
		JsonNode custom = input.snapshot().designSettings().custom();
		prompt.put("guidelines", custom == null ? "" : custom.path("guidelines").asString(""));
		var items = prompt.putArray("items");
		List<String> sourceLines = new ArrayList<>();
		for (OutlineService.Item item : outline.items()) {
			var entry = items.addObject().put("title", item.title()).put("summary", item.summary() == null ? "" : item.summary());
			var linked = entry.putArray("sourceNumbers");
			List<String> titles = new ArrayList<>();
			for (OutlineService.SourceRef ref : item.sourceRefs()) {
				int number = numbers.get(ref.materialId());
				linked.add(number);
				titles.add(evidence.get(number - 1).title());
			}
			sourceLines.add(sourceLine(titles));
		}
		var sources = prompt.putArray("sources");
		for (int index = 0; index < evidence.size(); index++) {
			Evidence source = evidence.get(index);
			sources.addObject().put("number", index + 1).put("title", source.title()).put("text", source.text());
		}

		Deck deck = parseResult(provider.generate(input.model(), json.writeValueAsString(prompt)), outline.items().size());
		List<Slide> slides = SlideLayouts.build(json, deck, theme(input.snapshot().designSettings()), sourceLines);
		try {
			slides.forEach(slide -> slide.elements()
				.forEach(element -> ElementRules.check(element.type(), element.content(), element.style(), null)));
		}
		catch (ApiException ex) {
			throw OpenAiPresentationClient.invalidResult();
		}
		return slides;
	}

	@Override
	@SuppressWarnings("unchecked")
	public ObjectNode publish(JobQueue.Job job, Object output) {
		current(job, input(job), false);
		UUID presentation = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO presentations (id, project_id, schema_version, canvas_width, canvas_height, canvas_unit)
				VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), 1, :width, :height, 'px')
				""").param("id", presentation.toString()).param("project", job.projectId().toString())
			.param("width", SlideLayouts.WIDTH).param("height", SlideLayouts.HEIGHT).update();
		List<Slide> slides = (List<Slide>) output;
		for (int position = 0; position < slides.size(); position++) {
			UUID slide = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO slides (id, project_id, presentation_id, generation, position, layout_id)
					VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:presentation), 1, :position, :layout)
					""").param("id", slide.toString()).param("project", job.projectId().toString())
				.param("presentation", presentation.toString()).param("position", position)
				.param("layout", slides.get(position).layout()).update();
			List<SlideLayouts.Element> elements = slides.get(position).elements();
			for (int z = 0; z < elements.size(); z++) {
				SlideLayouts.Element element = elements.get(z);
				jdbc.sql("""
						INSERT INTO slide_elements (id, project_id, slide_id, type, x, y, width, height, z_order, content, style)
						VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:project), UUID_TO_BIN(:slide), :type, :x, :y, :width, :height,
						        :z, CAST(:content AS JSON), CAST(:style AS JSON))
						""").param("id", UUID.randomUUID().toString()).param("project", job.projectId().toString())
					.param("slide", slide.toString()).param("type", element.type()).param("x", element.x())
					.param("y", element.y()).param("width", element.width()).param("height", element.height())
					.param("z", z).param("content", json.writeValueAsString(element.content()))
					.param("style", json.writeValueAsString(element.style())).update();
			}
		}
		ObjectNode payload = json.createObjectNode().put("presentationId", presentation.toString())
			.put("presentationVersion", 1).put("generation", 1);
		events.append(job.projectId(), "presentation.replaced", job.requesterId(), null, presentation, payload);
		return json.createObjectNode().put("type", "presentation").put("projectId", job.projectId().toString())
			.put("presentationId", presentation.toString()).put("presentationVersion", 1);
	}

	@Override
	public void settle(JobQueue.Job job, String status) {
		// 발행 전에는 공유 문서를 만들지 않는다
	}

	private Input input(JobQueue.Job job) {
		Input result = json.treeToValue(job.input().get("input"), Input.class);
		if (result.snapshot().schemaVersion() != SCHEMA_VERSION || !PROMPT_VERSION.equals(result.promptVersion())) {
			throw stale();
		}
		return result;
	}

	// 요청 뒤에 권한이나 입력이 바뀌었으면 만들지 않는다
	private List<Evidence> current(JobQueue.Job job, Input input, boolean withText) {
		try {
			access.requireMember(job.projectId(), job.requesterId());
		}
		catch (ApiException ex) {
			throw new JobFailure("JOB_PRESENTATION_ACCESS_REVOKED", "프로젝트 접근 권한이 없어 발표 자료를 만들지 않았어요.", false);
		}
		if (presentationExists(job.projectId())) {
			throw new JobFailure("JOB_INPUT_STALE", "이미 발표 자료가 있어 새로 만들지 않았어요.", false);
		}
		Snapshot snapshot = input.snapshot();
		if (outlines.get(job.requesterId(), job.projectId()).version() != snapshot.outline().version()
				|| designs.get(job.requesterId(), job.projectId()).version() != snapshot.designSettings().version()) {
			throw stale();
		}
		List<UUID> ids = snapshot.sources().stream().map(Source::materialId).toList();
		List<Evidence> evidence = readEvidence(job.projectId(), ids, withText);
		if (!evidence.stream().map(Evidence::reference).toList().equals(snapshot.sources())) {
			throw stale();
		}
		return evidence;
	}

	private boolean presentationExists(UUID projectId) {
		return jdbc.sql("SELECT COUNT(*) FROM presentations WHERE project_id = UUID_TO_BIN(?)")
			.param(projectId.toString()).query(Integer.class).single() > 0;
	}

	private List<Evidence> readEvidence(UUID projectId, List<UUID> ids, boolean withText) {
		return jdbc.sql("""
				SELECT BIN_TO_UUID(m.id) AS material_id, m.version AS material_version,
				       BIN_TO_UUID(r.id) AS revision_id, r.revision_no, m.title,
				       CASE WHEN :withText THEN LEFT(COALESCE(r.markdown, r.extracted_text, ''), 2000) ELSE '' END AS body
				FROM JSON_TABLE(:ids, '$[*]' COLUMNS (
				     position FOR ORDINALITY, material_id VARCHAR(36) PATH '$')) selected
				JOIN materials m ON m.id = UUID_TO_BIN(selected.material_id)
				JOIN material_revisions r ON r.id = m.current_revision_id AND r.project_id = m.project_id AND r.material_id = m.id
				WHERE m.project_id = UUID_TO_BIN(:project) AND m.state = 'ready' AND m.deleted_at IS NULL
				ORDER BY selected.position
				""").param("ids", json.writeValueAsString(ids)).param("project", projectId.toString())
			.param("withText", withText).query((rs, unused) -> new Evidence(
				new Source(UUID.fromString(rs.getString("material_id")), rs.getLong("material_version"),
					UUID.fromString(rs.getString("revision_id")), rs.getLong("revision_no")),
				rs.getString("title"), rs.getString("body"))).list();
	}

	// 템플릿은 미리보기의 배경색, 제목색과 템플릿별 강조색을, 직접 입력은 흰 배경과 대표색을 쓴다
	private Theme theme(DesignService.Settings design) {
		if ("template".equals(design.mode())) {
			JsonNode template = jdbc.sql("""
					SELECT JSON_OBJECT('key', template_key, 'preview', preview, 'fonts', fonts)
					FROM templates WHERE id = UUID_TO_BIN(?)
					""").param(design.templateId().toString()).query(String.class).optional()
				.map(json::readTree).orElse(json.createObjectNode());
			String heading = color(template.path("preview").path("headingColor"), "#212735");
			SlideLayouts.Style style = SlideLayouts.STYLES.getOrDefault(template.path("key").asString(""),
					new SlideLayouts.Style(heading, "rule"));
			return Theme.of(template.path("fonts").path(0).asString(FONT),
					color(template.path("preview").path("backgroundColor"), "#FFFFFF"), heading, style.accent(),
					style.motif());
		}
		// 프로젝트 글꼴은 화면이 글꼴 파일을 불러와 등록하는 이름으로 적는다
		JsonNode custom = design.custom();
		String font = custom.hasNonNull("fontAssetId") ? "ProjectFont-" + custom.get("fontAssetId").asString()
				: custom.path("font").asString("");
		String primary = color(custom.path("primaryColor"), "#2864E8");
		return Theme.of(font.isBlank() ? FONT : font, "#FFFFFF", primary, primary, "rule");
	}

	private static String color(JsonNode node, String fallback) {
		String value = node.asString("");
		return COLOR.matcher(value).matches() ? value.toUpperCase() : fallback;
	}

	private static String sourceLine(List<String> titles) {
		if (titles.isEmpty()) {
			return null;
		}
		List<String> shown = titles.stream().limit(SOURCE_TITLES_SHOWN).map(title -> {
			String value = title.strip().replaceAll("\\s+", " ");
			return value.codePointCount(0, value.length()) <= SOURCE_TITLE_MAX ? value
					: value.substring(0, value.offsetByCodePoints(0, SOURCE_TITLE_MAX)).strip();
		}).toList();
		String line = "출처: " + String.join(", ", shown);
		return titles.size() > shown.size() ? line + " 외 " + (titles.size() - shown.size()) + "건" : line;
	}

	private Deck parseResult(JsonNode generated, int itemCount) {
		try {
			OutlineInput.checkFields(generated, Set.of("title", "subtitle", "slides", "closing"), "presentation");
			JsonNode slides = generated.path("slides");
			if (!slides.isArray() || slides.size() != itemCount) {
				throw OpenAiPresentationClient.invalidResult();
			}
			List<Section> sections = new ArrayList<>();
			for (JsonNode slide : slides) {
				sections.add(section(slide));
			}
			return new Deck(line(generated.path("title"), SlideLayouts.DECK_TITLE_MAX),
					line(generated.path("subtitle"), SlideLayouts.SUBTITLE_MAX), List.copyOf(sections),
					section(generated.path("closing")));
		}
		catch (ApiException ex) {
			throw OpenAiPresentationClient.invalidResult();
		}
	}

	private static Section section(JsonNode node) {
		OutlineInput.checkFields(node, Set.of("layout", "title", "message", "points"), "slides");
		JsonNode points = node.path("points");
		if (!points.isArray() || points.isEmpty() || points.size() > SlideLayouts.POINTS_MAX) {
			throw OpenAiPresentationClient.invalidResult();
		}
		List<Point> lines = new ArrayList<>();
		for (JsonNode point : points) {
			OutlineInput.checkFields(point, Set.of("head", "body"), "points");
			lines.add(new Point(line(point.path("head"), SlideLayouts.HEAD_MAX),
					line(point.path("body"), SlideLayouts.POINT_MAX)));
		}
		// 모르는 배치는 목록으로 그린다. 핵심 문장은 비어 있어도 된다
		String layout = node.path("layout").asString("");
		String message = node.path("message").asString("").strip().replaceAll("\\s+", " ");
		if (message.codePointCount(0, message.length()) > SlideLayouts.MESSAGE_MAX) {
			throw OpenAiPresentationClient.invalidResult();
		}
		return new Section(SlideLayouts.LAYOUTS.contains(layout) ? layout : "list",
				line(node.path("title"), SlideLayouts.SLIDE_TITLE_MAX), message, List.copyOf(lines));
	}

	// 줄바꿈과 겹친 공백은 한 칸으로 줄이고 글상자에 넘치는 길이는 받지 않는다
	private static String line(JsonNode node, int max) {
		String value = OutlineInput.text(node, "presentation", Integer.MAX_VALUE).strip().replaceAll("\\s+", " ");
		if (value.isEmpty() || value.codePointCount(0, value.length()) > max) {
			throw OpenAiPresentationClient.invalidResult();
		}
		return value;
	}

	private static JobFailure stale() {
		return new JobFailure("JOB_INPUT_STALE", "저장한 발표 구성이나 디자인, 자료가 바뀌었어요. 최신 값으로 다시 만들어 주세요.", false);
	}
}
