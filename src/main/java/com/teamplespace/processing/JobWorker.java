package com.teamplespace.processing;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

// API 프로세스 안에서 도는 제한된 실행 풀. 요청 스레드에서 오래 걸리는 처리를 하지 않게 한다
// 실행 환경을 나누게 되면 같은 JobQueue를 쓰는 별도 프로세스로 옮긴다
@Component
@ConditionalOnProperty(name = "teamplespace.jobs.worker-enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {

	private final JobQueue queue;

	private final int concurrency;

	private final ExecutorService pool;

	private final String workerId = "api-" + UUID.randomUUID();

	private final Map<JobQueue.Claim, FutureTask<Void>> running = new ConcurrentHashMap<>();

	public JobWorker(JobQueue queue, @Value("${teamplespace.jobs.concurrency:2}") int concurrency) {
		this.queue = queue;
		this.concurrency = concurrency;
		this.pool = Executors.newFixedThreadPool(concurrency);
	}

	@Scheduled(fixedDelayString = "${teamplespace.jobs.poll-interval:1s}")
	public void poll() {
		queue.reapExpired();
		while (running.size() < concurrency) {
			Optional<JobQueue.Claim> claim = queue.claim(workerId);
			if (claim.isEmpty()) {
				return;
			}
			JobQueue.Claim next = claim.get();
			FutureTask<Void> task = new FutureTask<>(() -> {
				queue.run(next);
				return null;
			});
			running.put(next, task);
			pool.execute(() -> {
				try {
					task.run();
				}
				finally {
					running.remove(next);
				}
			});
		}
	}

	// 취소를 확정한 뒤 실행에 중단을 알린다. 늦은 결과는 임대 검사에서 거절한다
	@Scheduled(fixedDelayString = "${teamplespace.jobs.heartbeat-interval:15s}")
	public void heartbeat() {
		running.forEach((claim, task) -> {
			JobQueue.Lease lease = queue.heartbeat(claim);
			if (lease == JobQueue.Lease.CANCEL_REQUESTED) {
				queue.acknowledgeCancellation(claim);
				task.cancel(true);
			}
			else if (lease == JobQueue.Lease.LOST) {
				task.cancel(true);
			}
		});
	}

	@PreDestroy
	void stop() {
		pool.shutdownNow();
	}

}
