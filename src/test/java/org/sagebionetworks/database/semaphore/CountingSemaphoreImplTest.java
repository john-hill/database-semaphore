package org.sagebionetworks.database.semaphore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * This is a database level integration test for the CountingSemaphore.
 * In order to run this test you will need ensure the following system properties are set:
 * "-Djdbc.url=jdbc:mysql://localhost/semaphore"
 * "-Djdbc.username=your_username"
 * "-Djdbc.password=your_password"
 * 
 * To run in eclipse make sure the above properties are added to the "VM Argumetns" of the test.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.spb.xml" })
public class CountingSemaphoreImplTest {
	
	private static final Logger log = LogManager.getLogger(CountingSemaphoreImplTest.class);
	
	@Autowired
	CountingSemaphore semaphore;
	
	String key;
	
	@Before
	public void before(){
		semaphore.releaseAllLocks();
		key = "sampleKey";
	}
	
	@Test
	public void testAcquireRelease(){
		int maxLockCount = 2;
		long timeoutSec = 60;
		// get one lock
		String token1 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token1);
		// get another
		String token2 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token2);
		// Try for a third should not acquire a lock
		String token3 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertEquals(null, token3);
		// release
		semaphore.releaseLock(key, token2);
		// we should now be able to get a new lock
		token3 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token3);
	}
	
	@Test
	public void testLockExpired() throws InterruptedException{
		int maxLockCount = 1;
		long timeoutSec = 1;
		// get one lock
		String token1 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token1);
		// Should not be able to get a lock
		String token2 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertEquals(null, token2);
		// Wait for the lock first lock to expire
		Thread.sleep(timeoutSec*1000*2);
		// We should now be able to get the lock as the first is expired.
		token2 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token2);
	}
	
	@Test (expected=LockReleaseFailedException.class)
	public void testReleaseExpiredLock() throws InterruptedException{
		int maxLockCount = 1;
		long timeoutSec = 1;
		// get one lock
		String token1 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token1);
		// Wait until the lock expires
		Thread.sleep(timeoutSec*1000*2);
		// another should be able to get the lock
		String token2 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token2);
		// this should fail as the lock has already expired.
		semaphore.releaseLock(key, token1);
	}
	
	@Test
	public void testRefreshLockTimeout() throws InterruptedException{
		int maxLockCount = 1;
		long timeoutSec = 2;
		// get one lock
		String token1 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token1);
		// We should be able to refresh the lock.
		for(int i=0; i< timeoutSec+1; i++){
			semaphore.refreshLockTimeout(key, token1, timeoutSec);
			Thread.sleep(1000);
		}
		// The lock should still be held even though we have now exceeded to original timeout.
		semaphore.releaseLock(key, token1);
	}
	
	@Test (expected=LockReleaseFailedException.class)
	public void testRefreshExpiredLock() throws InterruptedException{
		int maxLockCount = 1;
		long timeoutSec = 1;
		// get one lock
		String token1 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token1);
		// Wait until the lock expires
		Thread.sleep(timeoutSec*1000*2);
		// another should be able to get the lock
		String token2 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token2);
		// this should fail as the lock has already expired.
		semaphore.refreshLockTimeout(key, token1, timeoutSec);
	}
	
	@Test (expected=LockReleaseFailedException.class)
	public void testReleaseLockAfterReleaseAllLocks(){
		int maxLockCount = 1;
		long timeoutSec = 1;
		// get one lock
		String token1 = semaphore.attemptToAcquireLock(key, timeoutSec, maxLockCount);
		assertNotNull(token1);
		// Force the release of all locks
		semaphore.releaseAllLocks();
		// Now try to release the lock
		semaphore.releaseLock(key, token1);
	}
	
	/**
	 * Test concurrent threads can acquire and release locks
	 * @throws Exception 
	 */
	@Test
	public void testConcurrent() throws Exception{
		int maxThreads = 25;
		long lockTimeoutSec = 2;
		int maxLockCount = maxThreads-1;
		ExecutorService executorService =Executors.newFixedThreadPool(maxThreads);
		List<Callable<Boolean>> runners = new LinkedList<Callable<Boolean>>();
		;
		for(int i=0; i<maxThreads; i++){
			TestRunner runner = new TestRunner(semaphore, key, lockTimeoutSec, maxLockCount);
			runners.add(runner);
		}
		// run all runners
		List<Future<Boolean>> futures = executorService.invokeAll(runners);
		int locksAcquired = 0;
		for(Future<Boolean> future: futures){
			if(future.get()){
				locksAcquired++;
			}
		}
		assertEquals("The number of locks acquired did not match the expected count.",maxLockCount, locksAcquired);
	}
	

	private class TestRunner implements Callable<Boolean> {
		CountingSemaphore semaphore;
		String key;
		long lockTimeoutSec;
		int maxLockCount;
		long sleepTimeMs;
		
		
		public TestRunner(CountingSemaphore semaphore, String key,
				long lockTimeoutSec, int maxLockCount) {
			super();
			this.semaphore = semaphore;
			this.key = key;
			this.lockTimeoutSec = lockTimeoutSec;
			this.maxLockCount = maxLockCount;
			this.sleepTimeMs = lockTimeoutSec/2*1000;
		}

		public Boolean call() throws Exception {
			String token = semaphore.attemptToAcquireLock(key, lockTimeoutSec, maxLockCount);
			if(token != null){
				try {
					Thread.sleep(sleepTimeMs);
					// the lock was acquired and held
					return true;
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				} finally {
					semaphore.releaseLock(key, token);
				}
			}else{
				// lock was not acquired
				return false;
			}
		}
	}

}
