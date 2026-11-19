package com.teamplespace.processing;

import tools.jackson.databind.node.ObjectNode;

// 작업 종류 하나의 처리 방법. 자료 분석, 이후의 생성과 내보내기가 각각 구현한다
public interface JobHandler {

	String type();

	// 임대를 잡은 동안 트랜잭션 밖에서 실행한다. 오래 걸리는 처리는 여기서 하고 저장은 하지 않는다
	Object execute(JobQueue.Job job) throws Exception;

	// 결과를 저장한다. 프로젝트와 작업 잠금을 잡은 완료 트랜잭션 안에서 불리고 돌려준 값이 resultRef가 된다
	ObjectNode publish(JobQueue.Job job, Object output);

	// 실패나 취소로 끝났을 때 대상의 상태를 정리한다. 종료와 같은 트랜잭션 안에서 불린다
	void settle(JobQueue.Job job, String status);

}
