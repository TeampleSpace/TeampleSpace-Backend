package com.teamplespace.workspace.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamplespace.collaboration.service.ProjectEvents;
import com.teamplespace.identity.config.AuthSettings;
import com.teamplespace.identity.service.AuthSecrets;
import com.teamplespace.identity.service.AuthService;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.platform.idempotency.IdempotencyStore;
import com.teamplespace.platform.json.CanonicalJson;

import tools.jackson.databind.ObjectMapper;

@Service
public class WorkspaceService {

	public record CreateProject(String name,String topic,String presentationConstraints) { }
	public record UpdateProject(Long expectedVersion,String name,String topic,String presentationConstraints) { }
	public record DeleteProject(Long expectedVersion,String confirmationName) { }
	public record RemoveMember(Long expectedMembershipVersion,String clientMutationId) { }
	public record Ownership(UUID targetUserId,Long expectedVersion) { }
	private final JdbcClient jdbc;
	private final AuthSecrets secrets;
	private final AuthSettings settings;
	private final IdempotencyStore idempotency;
	private final ProjectEvents events;
	private final ObjectMapper json=new ObjectMapper();

	public WorkspaceService(JdbcClient jdbc,AuthSecrets secrets,AuthSettings settings,IdempotencyStore idempotency,ProjectEvents events) {
		this.jdbc=jdbc;this.secrets=secrets;this.settings=settings;this.idempotency=idempotency;this.events=events;
	}

	@Transactional(readOnly=true)
	public Map<String,Object> list(UUID actor,String query,String cursor,Integer limit) {
		int size=limit(limit);String after=cursor(cursor);String search=query==null?"":query.strip();
		if(search.length()>255)throw new ApiException(ErrorCode.VALIDATION_FAILED);
		var ids=jdbc.sql("""
				SELECT BIN_TO_UUID(p.id) FROM projects p JOIN project_memberships m ON m.project_id=p.id
				WHERE m.user_id=UUID_TO_BIN(?) AND m.state='active' AND p.status='active'
				AND p.owner_user_id IS NOT NULL AND (?='' OR LOCATE(?,p.name)>0)
				AND (? IS NULL OR p.id>UUID_TO_BIN(?)) ORDER BY p.id LIMIT ?
				""").params(actor.toString(),search,search,after,after,size+1).query(String.class).list();
		List<Map<String,Object>> items=new ArrayList<>();
		for(String id:ids.stream().limit(size).toList())items.add(project(UUID.fromString(id)));
		return page(items,ids.size()>size?ids.get(size-1):null);
	}

	@Transactional
	public Map<String,Object> create(UUID actor,CreateProject input,String keyValue) {
		lockUsers(actor);validateName(input.name());validateOptional(input.topic(),10000);validateOptional(input.presentationConstraints(),10000);
		var key=key(actor,"POST","/v1/projects",keyValue);
		var stored=idempotency.findOrClaim(key,CanonicalJson.sha256(json.valueToTree(input)));
		if(stored.isPresent())return read(stored.get().body());
		UUID id=UUID.randomUUID();
		jdbc.sql("INSERT INTO projects(id,name,owner_user_id,topic,presentation_constraints) VALUES(UUID_TO_BIN(?),?,UUID_TO_BIN(?),?,?)")
			.params(id.toString(),input.name().strip(),actor.toString(),nullable(input.topic()),nullable(input.presentationConstraints())).update();
		jdbc.sql("INSERT INTO project_memberships(project_id,user_id,state) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'active')").params(id.toString(),actor.toString()).update();
		Map<String,Object> result=project(id);idempotency.complete(key,true,201,json.writeValueAsString(result),id);return result;
	}

	@Transactional(readOnly=true)
	public Map<String,Object> get(UUID actor,UUID id) { access(actor,id);return project(id); }

	@Transactional
	public Map<String,Object> update(UUID actor,UUID id,UpdateProject input) {
		lockUsers(actor);lockProject(id);owner(actor,id);version(id,input.expectedVersion());
		if(input.name()!=null)validateName(input.name());validateOptional(input.topic(),10000);validateOptional(input.presentationConstraints(),10000);
		if(input.name()==null&&input.topic()==null&&input.presentationConstraints()==null)throw new ApiException(ErrorCode.VALIDATION_FAILED);
		if(input.name()!=null)jdbc.sql("UPDATE projects SET name=? WHERE id=UUID_TO_BIN(?)").params(input.name().strip(),id.toString()).update();
		if(input.topic()!=null)jdbc.sql("UPDATE projects SET topic=? WHERE id=UUID_TO_BIN(?)").params(nullable(input.topic()),id.toString()).update();
		if(input.presentationConstraints()!=null)jdbc.sql("UPDATE projects SET presentation_constraints=? WHERE id=UUID_TO_BIN(?)").params(nullable(input.presentationConstraints()),id.toString()).update();
		jdbc.sql("UPDATE projects SET version=version+1 WHERE id=UUID_TO_BIN(?)").param(id.toString()).update();return project(id);
	}

	@Transactional
	public void delete(UUID actor,UUID id,DeleteProject input) {
		lockUsers(actor);lockProject(id);owner(actor,id);version(id,input.expectedVersion());
		if(!project(id).get("name").equals(input.confirmationName()))throw new ApiException(ErrorCode.CONFIRMATION_MISMATCH);
		jdbc.sql("UPDATE projects SET status='deleted',deleted_at=UTC_TIMESTAMP(6),version=version+1 WHERE id=UUID_TO_BIN(?)").param(id.toString()).update();
		jdbc.sql("UPDATE project_invitations SET state='revoked' WHERE project_id=UUID_TO_BIN(?)").param(id.toString()).update();
	}

	@Transactional(readOnly=true)
	public Map<String,Object> members(UUID actor,UUID id,String cursor,Integer limit) {
		access(actor,id);int size=limit(limit);String after=cursor(cursor);
		var items=jdbc.sql("""
				SELECT BIN_TO_UUID(m.user_id) userId,u.display_name name,m.version membershipVersion,p.owner_user_id=m.user_id isOwner
				FROM project_memberships m JOIN users u ON u.id=m.user_id JOIN projects p ON p.id=m.project_id
				WHERE m.project_id=UUID_TO_BIN(?) AND m.state='active' AND u.status='active'
				AND (? IS NULL OR m.user_id>UUID_TO_BIN(?)) ORDER BY m.user_id LIMIT ?
				""").params(id.toString(),after,after,size+1).query((rs,n)->Map.<String,Object>of("userId",rs.getString("userId"),"name",rs.getString("name"),
				"membershipVersion",rs.getLong("membershipVersion"),"isOwner",rs.getBoolean("isOwner"),"role",rs.getBoolean("isOwner")?"owner":"member")).list();
		return page(items.stream().limit(size).toList(),items.size()>size?(String)items.get(size-1).get("userId"):null);
	}

	@Transactional
	public Map<String,Object> remove(UUID actor,UUID id,UUID target,RemoveMember input,String keyValue) {
		var lockedUsers=lockUserRows(actor,target);requireReadyUser(lockedUsers.get(actor));lockProject(id);
		Map<String,Object> project=project(id);
		boolean self=actor.equals(target);
		if(!self)owner(actor,id);
		var key=key(actor,"DELETE","/v1/projects/"+id+"/members/"+target,keyValue);
		var stored=idempotency.findOrClaim(key,CanonicalJson.sha256(json.valueToTree(input)));
		if(stored.isPresent())return read(stored.get().body());
		requireReadyUser(lockedUsers.get(target));
		access(actor,id);
		if(project.get("ownerId").equals(target.toString()))throw new ApiException(ErrorCode.OWNER_TRANSFER_REQUIRED);
		var member=jdbc.sql("SELECT state,version FROM project_memberships WHERE project_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?)")
			.params(id.toString(),target.toString()).query().listOfRows().stream().findFirst().orElseThrow(()->new ApiException(ErrorCode.MEMBER_NOT_ACTIVE));
		if(!member.get("state").equals("active"))throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE);
		if(input.expectedMembershipVersion()==null || ((Number)member.get("version")).longValue()!=input.expectedMembershipVersion())throw new ApiException(ErrorCode.VERSION_CONFLICT);
		if(input.clientMutationId()==null || input.clientMutationId().isBlank() || input.clientMutationId().length()>64)throw new ApiException(ErrorCode.VALIDATION_FAILED);
		long next=((Number)member.get("version")).longValue()+1;
		jdbc.sql("UPDATE project_memberships SET state=?,version=version+1,left_at=UTC_TIMESTAMP(6) WHERE project_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?)")
			.params(self?"left":"removed",id.toString(),target.toString()).update();
		long event=events.append(id,"membership.changed",actor,input.clientMutationId(),target,json.valueToTree(Map.of("userId",target.toString(),"state",self?"left":"removed","membershipVersion",next)));
		var result=Map.<String,Object>of("clientMutationId",input.clientMutationId(),"tombstone",Map.of("type","membership","id",target.toString(),"deleted",true),"membershipVersion",next,"eventId",Long.toString(event));
		idempotency.complete(key,true,200,json.writeValueAsString(result),target);return result;
	}

	@Transactional
	public Map<String,Object> ownership(UUID actor,UUID id,Ownership input) {
		if(input.targetUserId()==null || input.targetUserId().equals(actor))throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE);
		lockUsers(actor,input.targetUserId());lockProject(id);owner(actor,id);version(id,input.expectedVersion());
		if(jdbc.sql("SELECT COUNT(*) FROM project_memberships WHERE project_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?) AND state='active'")
			.params(id.toString(),input.targetUserId().toString()).query(Integer.class).single()!=1)throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE);
		jdbc.sql("UPDATE projects SET owner_user_id=UUID_TO_BIN(?),version=version+1 WHERE id=UUID_TO_BIN(?)").params(input.targetUserId().toString(),id.toString()).update();
		jdbc.sql("UPDATE project_memberships SET version=version+1 WHERE project_id=UUID_TO_BIN(?) AND user_id IN (UUID_TO_BIN(?),UUID_TO_BIN(?))")
			.params(id.toString(),actor.toString(),input.targetUserId().toString()).update();return project(id);
	}

	@Transactional
	public Map<String,Object> invitation(UUID actor,UUID id,boolean rotate) {
		lockUsers(actor);lockProject(id);access(actor,id);
		var existing=jdbc.sql("SELECT token_ciphertext,expires_at FROM project_invitations WHERE project_id=UUID_TO_BIN(?) AND state='active'").param(id.toString()).query().listOfRows().stream().findFirst();
		if(existing.isPresent()&&!rotate&&AuthService.instant(existing.get().get("expires_at")).isAfter(Instant.now())) {
			return Map.of("url",settings.origin()+"/invite/"+secrets.decrypt((String)existing.get().get("token_ciphertext")),"expiresAt",AuthService.instant(existing.get().get("expires_at")));
		}
		jdbc.sql("UPDATE project_invitations SET state='revoked' WHERE project_id=UUID_TO_BIN(?) AND state='active'").param(id.toString()).update();
		String token=secrets.token();Instant expires=Instant.now().plusSeconds(604800);
		jdbc.sql("INSERT INTO project_invitations(id,project_id,token_hash,token_ciphertext,issued_by,state,expires_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,UUID_TO_BIN(?),'active',?)")
			.params(UUID.randomUUID().toString(),id.toString(),secrets.hash(token),secrets.encrypt(token),actor.toString(),Timestamp.from(expires)).update();
		return Map.of("url",settings.origin()+"/invite/"+token,"expiresAt",expires);
	}

	@Transactional(readOnly=true)
	public Map<String,Object> preview(UUID actor,String token) {
		Map<String,Object> invitation=findInvitation(token);UUID id=UUID.fromString((String)invitation.get("project_id"));
		Map<String,Object> project=project(id);boolean member=actor!=null&&activeMember(actor,id);
		return Map.of("projectName",project.get("name"),"inviterName",invitation.get("inviter_name"),"expiresAt",AuthService.instant(invitation.get("expires_at")),"alreadyMember",member);
	}

	@Transactional
	public Map<String,Object> accept(UUID actor,String token) {
		lockUsers(actor);Map<String,Object> initial=findInvitation(token);UUID id=UUID.fromString((String)initial.get("project_id"));
		lockProject(id);Map<String,Object> invitation=lockInvitation(token);
		var member=jdbc.sql("SELECT state,left_at FROM project_memberships WHERE project_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?)")
			.params(id.toString(),actor.toString()).query().listOfRows().stream().findFirst();
		if(member.isPresent()&&member.get().get("state").equals("active"))return Map.of("projectId",id,"alreadyMember",true);
		if(member.isPresent()&&member.get().get("state").equals("removed")
				&&!AuthService.instant(invitation.get("created_at")).isAfter(AuthService.instant(member.get().get("left_at"))))throw new ApiException(ErrorCode.ACCESS_DENIED);
		jdbc.sql("INSERT INTO project_memberships(project_id,user_id,state) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'active') ON DUPLICATE KEY UPDATE state='active',version=version+1,joined_at=UTC_TIMESTAMP(6),left_at=NULL")
			.params(id.toString(),actor.toString()).update();return Map.of("projectId",id,"alreadyMember",false);
	}

	private Map<String,Object> lockInvitation(String token) {
		Map<String,Object> row=jdbc.sql("SELECT state,expires_at,created_at FROM project_invitations WHERE token_hash=? FOR UPDATE")
			.param(secrets.hash(token)).query().singleRow();
		if(!row.get("state").equals("active"))throw new ApiException(ErrorCode.INVITE_INVALID);
		if(!AuthService.instant(row.get("expires_at")).isAfter(Instant.now()))throw new ApiException(ErrorCode.INVITE_EXPIRED);
		return row;
	}

	private Map<String,Object> findInvitation(String token) {
		if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ApiException(ErrorCode.INVITE_INVALID);
		Map<String,Object> row=jdbc.sql("SELECT BIN_TO_UUID(i.project_id) project_id,i.state,i.expires_at,i.created_at,u.display_name inviter_name,p.status project_status FROM project_invitations i JOIN users u ON u.id=i.issued_by JOIN projects p ON p.id=i.project_id WHERE i.token_hash=?")
			.param(secrets.hash(token)).query().listOfRows().stream().findFirst().orElseThrow(()->new ApiException(ErrorCode.INVITE_INVALID));
		if(!row.get("state").equals("active")||!row.get("project_status").equals("active"))throw new ApiException(ErrorCode.INVITE_INVALID);
		if(AuthService.instant(row.get("expires_at")).isBefore(Instant.now()))throw new ApiException(ErrorCode.INVITE_EXPIRED);
		return row;
	}

	private Map<String,Object> project(UUID id) {
		return jdbc.sql("SELECT BIN_TO_UUID(id) id,name,topic,presentation_constraints,BIN_TO_UUID(owner_user_id) owner_id,version,created_at,updated_at,(SELECT COUNT(*) FROM project_memberships m JOIN users u ON u.id=m.user_id WHERE m.project_id=projects.id AND m.state='active' AND u.status='active') member_count FROM projects WHERE id=UUID_TO_BIN(?) AND status='active' AND owner_user_id IS NOT NULL")
			.param(id.toString()).query((rs,row)->{
				Map<String,Object> result=new LinkedHashMap<>();result.put("id",rs.getString("id"));result.put("name",rs.getString("name"));result.put("topic",rs.getString("topic"));
				result.put("presentationConstraints",rs.getString("presentation_constraints"));result.put("ownerId",rs.getString("owner_id"));result.put("version",rs.getLong("version"));
				result.put("memberCount",rs.getInt("member_count"));result.put("createdAt",rs.getTimestamp("created_at").toInstant());result.put("updatedAt",rs.getTimestamp("updated_at").toInstant());return result;
			}).optional().orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
	}

	private void access(UUID actor,UUID id) {
		project(id);
		var state=jdbc.sql("SELECT state FROM project_memberships WHERE project_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?)").params(id.toString(),actor.toString()).query(String.class).optional();
		if(state.isEmpty())throw new ApiException(ErrorCode.NOT_FOUND);
		if(!state.get().equals("active"))throw new ApiException(ErrorCode.ACCESS_DENIED);
	}
	private boolean activeMember(UUID actor,UUID id) { return jdbc.sql("SELECT COUNT(*) FROM project_memberships WHERE project_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?) AND state='active'").params(id.toString(),actor.toString()).query(Integer.class).single()==1; }
	private void owner(UUID actor,UUID id) { access(actor,id);if(!project(id).get("ownerId").equals(actor.toString()))throw new ApiException(ErrorCode.FORBIDDEN); }
	private void version(UUID id,Long expected) { if(expected==null||expected<1)throw new ApiException(ErrorCode.VALIDATION_FAILED);if(((Number)project(id).get("version")).longValue()!=expected)throw new ApiException(ErrorCode.VERSION_CONFLICT); }
	private void lockProject(UUID id) { jdbc.sql("SELECT id FROM projects WHERE id=UUID_TO_BIN(?) AND status='active' FOR UPDATE").param(id.toString()).query().listOfRows().stream().findFirst().orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND)); }
	private void lockUsers(UUID...ids) { lockUserRows(ids).values().forEach(this::requireReadyUser); }
	private Map<UUID,Map<String,Object>> lockUserRows(UUID...ids) {
		Map<UUID,Map<String,Object>> users=new LinkedHashMap<>();
		for(UUID id:List.of(ids).stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList()) {
			var row=jdbc.sql("SELECT status,onboarding_status FROM users WHERE id=UUID_TO_BIN(?) FOR UPDATE")
				.param(id.toString()).query().listOfRows().stream().findFirst().orElse(Map.of());
			users.put(id,row);
		}
		return users;
	}
	private void requireReadyUser(Map<String,Object> row) {
		if(!"active".equals(row.get("status")))throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE);
		if(!"completed".equals(row.get("onboarding_status")))throw new ApiException(ErrorCode.ONBOARDING_REQUIRED);
	}

	private IdempotencyStore.Key key(UUID actor,String method,String path,String value) { if(value==null||value.isBlank()||value.length()>128)throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);return IdempotencyStore.Key.of(actor,method,path,value); }
	private void validateName(String value) { if(value==null||value.isBlank()||value.length()>255)throw new ApiException(ErrorCode.VALIDATION_FAILED); }
	private void validateOptional(String value,int max) { if(value!=null&&value.length()>max)throw new ApiException(ErrorCode.VALIDATION_FAILED); }
	private String nullable(String value) { return value==null||value.isBlank()?null:value.strip(); }
	private int limit(Integer value) { int size=value==null?30:value;if(size<1||size>100)throw new ApiException(ErrorCode.VALIDATION_FAILED);return size; }
	private String cursor(String value) { if(value==null||value.isEmpty())return null;try{return UUID.fromString(value).toString();}catch(IllegalArgumentException ex){throw new ApiException(ErrorCode.INVALID_CURSOR);} }
	private Map<String,Object> page(List<Map<String,Object>> items,String next) { Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("nextCursor",next);return result; }
	@SuppressWarnings("unchecked") private Map<String,Object> read(String value) { return json.readValue(value,Map.class); }
}
