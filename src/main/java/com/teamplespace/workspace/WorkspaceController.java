package com.teamplespace.workspace;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.teamplespace.identity.AuthService;
import com.teamplespace.workspace.application.WorkspaceService;
import com.teamplespace.workspace.application.WorkspaceService.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1")
public class WorkspaceController {

	public record InvitationRequest(boolean rotate) { }
	private final WorkspaceService workspace;
	private final AuthService auth;
	public WorkspaceController(WorkspaceService workspace,AuthService auth) {this.workspace=workspace;this.auth=auth;}
	private UUID actor(HttpServletRequest req) {return auth.requireOnboarded(req).userId();}

	@GetMapping("/projects") Map<String,Object> projects(@RequestParam(required=false)String q,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest req) {return workspace.list(actor(req),q,cursor,limit);}
	@PostMapping("/projects") ResponseEntity<?> create(@RequestBody CreateProject input,@RequestHeader(name="Idempotency-Key",required=false)String key,HttpServletRequest req) {return ResponseEntity.status(201).body(workspace.create(actor(req),input,key));}
	@GetMapping("/projects/{id}") Map<String,Object> project(@PathVariable UUID id,HttpServletRequest req) {return workspace.get(actor(req),id);}
	@PatchMapping("/projects/{id}") Map<String,Object> update(@PathVariable UUID id,@RequestBody UpdateProject input,HttpServletRequest req) {return workspace.update(actor(req),id,input);}
	@DeleteMapping("/projects/{id}") ResponseEntity<?> delete(@PathVariable UUID id,@RequestBody DeleteProject input,HttpServletRequest req) {workspace.delete(actor(req),id,input);return ResponseEntity.noContent().build();}
	@GetMapping("/projects/{id}/members") Map<String,Object> members(@PathVariable UUID id,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest req) {return workspace.members(actor(req),id,cursor,limit);}
	@DeleteMapping("/projects/{id}/members/{userId}") Map<String,Object> remove(@PathVariable UUID id,@PathVariable UUID userId,@RequestBody RemoveMember input,@RequestHeader(name="Idempotency-Key",required=false)String key,HttpServletRequest req) {return workspace.remove(actor(req),id,userId,input,key);}
	@PostMapping("/projects/{id}/ownership") Map<String,Object> ownership(@PathVariable UUID id,@RequestBody Ownership input,HttpServletRequest req) {return workspace.ownership(actor(req),id,input);}
	@PostMapping("/projects/{id}/invitations") Map<String,Object> invitation(@PathVariable UUID id,@RequestBody InvitationRequest input,HttpServletRequest req) {return workspace.invitation(actor(req),id,input.rotate());}
	@GetMapping("/invitations/{token}") Map<String,Object> preview(@PathVariable String token,HttpServletRequest req) {var session=auth.session(req);return workspace.preview(session==null?null:session.userId(),token);}
	@PostMapping("/invitations/{token}/accept") Map<String,Object> accept(@PathVariable String token,HttpServletRequest req) {return workspace.accept(actor(req),token);}
}
