import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Tomcat 스레드 수만큼 동시에 Argon2를 돌렸을 때 힙이 어떻게 되는지 잰다. */
public class Argon2Concurrency {
    public static void main(String[] args) throws Exception {
        int concurrency = Integer.parseInt(args[0]);
        var encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        var pool = Executors.newFixedThreadPool(concurrency);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(concurrency);
        var failures = new AtomicInteger();
        long maxHeap = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        for (int i = 0; i < concurrency; i++) {
            pool.submit(() -> {
                try { start.await(); encoder.encode("pw-under-test"); }
                catch (Throwable t) { failures.incrementAndGet(); System.out.println("  실패: " + t.getClass().getSimpleName()); }
                finally { done.countDown(); }
            });
        }
        long t0 = System.nanoTime();
        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        var rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        System.out.printf("동시 %d개 | 힙상한 %dMB | 소요 %dms | 완료=%s | 실패 %d | 종료직후 사용힙 %dMB%n",
                concurrency, maxHeap, elapsed, finished, failures.get(), used);
        pool.shutdownNow();
    }
}
