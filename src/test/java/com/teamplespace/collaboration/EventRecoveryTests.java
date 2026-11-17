package com.teamplespace.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.teamplespace.collaboration.application.ProjectEvents;
import com.teamplespace.collaboration.application.ProjectEvents.Replay;
import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.security.Actor;
import com.teamplespace.presentation.application.ElementPatchService;
import com.teamplespace.presentation.application.PresentationSnapshots;
import com.teamplespace.presentation.application.PresentationSnapshots.Snapshot;
import com.teamplespace.support.DocumentFixture;
import com.teamplespace.support.TestClockConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 스냅샷과 이벤트 커서의 일관성, 재연결, 중복과 누락 복구를 실제 MySQL에서 확인
@SpringBootTest
@Import(TestClockConfig.class)
class EventRecoveryTests {

	@Autowired
	ElementPatchService service;

	@Autowired
	PresentationSnapshots snapshots;

	@Autowired
	ProjectEvents events;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	DocumentFixture fixture;

	Actor userA;

	Actor userB;

	UUID projectId;

	final Map<UUID, UUID> groups = new HashMap<>();

	int requests;

	@BeforeEach
	void setUp() {
		fixture = new DocumentFixture(jdbc);
		fixture.reset();
		userA = fixture.userA;
		userB = fixture.userB;
		projectId = fixture.projectId;
	}

	@Test
	void snapshotCursorMatchesTheDocumentAndLaterChangesFollowIt() {
		save(userA, fixture.elementA, 1, "스냅샷 전 1");
		save(userA, fixture.elementA, 2, "스냅샷 전 2");

		Snapshot snapshot = snapshots.read(userB, projectId);

		assertThat(snapshot.eventCursor()).isEqualTo(Long.toString(fixture.eventSeq()));
		assertThat(snapshot.presentation().get("version").asLong()).isEqualTo(3);
		assertThat(ContractSchemas.validate("document.schema.json", snapshot.presentation().toString())).isEmpty();

		save(userA, fixture.elementA, 3, "스냅샷 후 1");
		save(userB, fixture.elementB, 1, "스냅샷 후 2");
		Replay replay = events.after(userB, projectId, snapshot.eventCursor(), 100);

		// 커서 바로 다음부터 빠짐없이 순서대로 온다
		assertThat(replay.snapshotRequired()).isFalse();
		long expected = Long.parseLong(snapshot.eventCursor());
		for (JsonNode event : replay.events()) {
			expected++;
			assertThat(event.get("eventId").asString()).isEqualTo(Long.toString(expected));
			assertThat(ContractSchemas.validate("api.schema.json#/$defs/Event", event.toString())).isEmpty();
		}
		assertThat(expected).isEqualTo(fixture.eventSeq());
		// 스냅샷에 이후 이벤트를 반영하면 현재 문서와 같다
		Client client = new Client(snapshot);
		client.receive(replay.events());
		assertThat(client.texts.get(fixture.elementA.toString())).isEqualTo("스냅샷 후 1");
		assertThat(client.texts.get(fixture.elementB.toString())).isEqualTo("스냅샷 후 2");
		assertThat(client.versions.get(fixture.elementA.toString())).isEqualTo(4);
	}

	@Test
	void snapshotTakenWhileSavingStaysConsistent() throws Exception {
		AtomicBoolean writing = new AtomicBoolean(true);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			Future<?> writer = threads.submit(() -> {
				try {
					for (int i = 1; i <= 40; i++) {
						save(userA, fixture.elementA, i, "입력 " + i);
					}
				}
				finally {
					writing.set(false);
				}
			});
			Future<List<Snapshot>> reader = threads.submit(() -> {
				List<Snapshot> taken = new ArrayList<>();
				while (writing.get()) {
					taken.add(snapshots.read(userB, projectId));
				}
				taken.add(snapshots.read(userB, projectId));
				return taken;
			});
			writer.get(60, TimeUnit.SECONDS);

			List<Snapshot> taken = reader.get(60, TimeUnit.SECONDS);

			// 문서 버전은 커서까지의 요소 변경 수와 항상 맞는다
			assertThat(taken).hasSizeGreaterThan(1);
			for (Snapshot snapshot : taken) {
				long updates = jdbc
					.sql("SELECT COUNT(*) FROM project_events WHERE type = 'element.updated' AND seq <= ?")
					.param(Long.parseLong(snapshot.eventCursor()))
					.query(Long.class)
					.single();
				assertThat(snapshot.presentation().get("version").asLong()).as("커서 " + snapshot.eventCursor())
					.isEqualTo(1 + updates);
			}
			assertThat(taken.get(taken.size() - 1).presentation().get("version").asLong()).isEqualTo(41);
		}
		finally {
			threads.shutdownNow();
		}
	}

	@Test
	void reconnectingWithLastEventIdSkipsReceivedEventsAndDuplicatesApplyOnce() {
		Client client = new Client(snapshots.read(userB, projectId));
		save(userA, fixture.elementA, 1, "입력 1");
		save(userA, fixture.elementA, 2, "입력 2");
		client.receive(events.after(userB, projectId, client.lastEventId, 100).events());
		String received = client.lastEventId;
		save(userA, fixture.elementA, 3, "입력 3");

		// 연결이 끊긴 뒤 마지막으로 받은 식별자부터 다시 받는다
		List<JsonNode> resumed = events.after(userB, projectId, received, 100).events();

		assertThat(resumed).isNotEmpty();
		assertThat(resumed).allMatch(event -> Long.parseLong(event.get("eventId").asString()) > Long.parseLong(received));
		client.receive(resumed);
		int applied = client.applied;
		// 이미 받은 이벤트가 다시 와도 한 번만 반영한다
		client.receive(events.after(userB, projectId, "0", 100).events());
		assertThat(client.applied).isEqualTo(applied);
		assertThat(client.texts.get(fixture.elementA.toString())).isEqualTo("입력 3");
		assertThat(client.lastEventId).isEqualTo(Long.toString(fixture.eventSeq()));
	}

	@Test
	void missingEventIsRecoveredFromTheLastAppliedEvent() {
		Client client = new Client(snapshots.read(userB, projectId));
		for (int i = 1; i <= 4; i++) {
			save(userA, fixture.elementA, i, "입력 " + i);
		}
		List<JsonNode> delivered = new ArrayList<>(events.after(userB, projectId, "0", 100).events());
		delivered.remove(2);

		// 순번이 건너뛰면 그 뒤는 반영하지 않고 멈춘다
		client.receive(delivered);
		assertThat(client.lastEventId).isEqualTo("2");

		// 마지막으로 반영한 식별자 다음부터 다시 받아 채운다
		client.receive(events.after(userB, projectId, client.lastEventId, 100).events());
		assertThat(client.lastEventId).isEqualTo(Long.toString(fixture.eventSeq()));
		assertThat(client.texts.get(fixture.elementA.toString())).isEqualTo("입력 4");
		assertThat(client.versions.get(fixture.elementA.toString())).isEqualTo(5);
	}

	@Test
	void unusableCursorRequiresANewSnapshot() {
		for (int i = 1; i <= 4; i++) {
			save(userA, fixture.elementA, i, "입력 " + i);
		}

		// 아직 없는 순번이나 잘못된 값으로는 이어 받을 수 없다
		assertThat(events.after(userB, projectId, "999", 100).snapshotRequired()).isTrue();
		assertThat(events.after(userB, projectId, "abc", 100).snapshotRequired()).isTrue();
		assertThat(events.after(userB, projectId, "-1", 100).snapshotRequired()).isTrue();
		// 보관 기간이 지나 앞 구간이 정리된 경우
		jdbc.sql("DELETE FROM project_events WHERE seq <= 3").update();
		assertThat(events.after(userB, projectId, "1", 100).snapshotRequired()).isTrue();
		assertThat(events.after(userB, projectId, "3", 100).snapshotRequired()).isFalse();

		// 스냅샷을 다시 읽으면 그 커서부터 이어 받을 수 있다
		Snapshot snapshot = snapshots.read(userB, projectId);
		Replay replay = events.after(userB, projectId, snapshot.eventCursor(), 100);
		assertThat(replay.snapshotRequired()).isFalse();
		assertThat(replay.events()).isEmpty();
		assertThat(new Client(snapshot).texts.get(fixture.elementA.toString())).isEqualTo("입력 4");
	}

	@Test
	void removedMemberCannotReadSnapshotOrEvents() {
		save(userA, fixture.elementA, 1, "입력");
		jdbc.sql("UPDATE project_memberships SET state = 'removed' WHERE user_id = UUID_TO_BIN(?)")
			.param(userB.userId().toString())
			.update();

		assertThat(catchThrowableOfType(ApiException.class, () -> snapshots.read(userB, projectId)).code())
			.isEqualTo(ErrorCode.FORBIDDEN);
		assertThat(catchThrowableOfType(ApiException.class, () -> events.after(userB, projectId, "0", 100)).code())
			.isEqualTo(ErrorCode.FORBIDDEN);
	}

	// 요소마다 하나의 편집 묶음으로 이어서 저장한다
	void save(Actor actor, UUID element, long baseVersion, String text) {
		int request;
		synchronized (this) {
			request = ++requests;
		}
		ObjectNode body = json.createObjectNode();
		body.put("generation", 1);
		body.put("baseVersion", baseVersion);
		body.put("editGroupId", groups.computeIfAbsent(element, key -> UUID.randomUUID()).toString());
		body.put("clientMutationId", "mut-" + request);
		body.putObject("patch").putObject("content").put("text", text);
		assertThat(service.patch(actor, fixture.slideId, element, "key-" + request, body).status()).isEqualTo(200);
	}

	// 프론트의 이벤트 반영 규칙을 흉내 낸 최소 클라이언트
	static class Client {

		final Map<String, String> texts = new HashMap<>();

		final Map<String, Long> versions = new HashMap<>();

		String lastEventId;

		int applied;

		Client(Snapshot snapshot) {
			lastEventId = snapshot.eventCursor();
			for (JsonNode slide : snapshot.presentation().get("slides")) {
				for (JsonNode element : slide.get("elements")) {
					put(element);
				}
			}
		}

		void receive(List<JsonNode> events) {
			for (JsonNode event : events) {
				long seq = Long.parseLong(event.get("eventId").asString());
				long last = Long.parseLong(lastEventId);
				if (seq <= last) {
					continue;
				}
				if (seq != last + 1) {
					return;
				}
				if (event.get("type").asString().equals("element.updated")) {
					JsonNode element = event.get("payload").get("resource");
					if (element.get("version").asLong() > versions.get(element.get("id").asString())) {
						put(element);
					}
				}
				lastEventId = event.get("eventId").asString();
				applied++;
			}
		}

		private void put(JsonNode element) {
			texts.put(element.get("id").asString(), element.get("content").get("text").asString());
			versions.put(element.get("id").asString(), element.get("version").asLong());
		}

	}

}
