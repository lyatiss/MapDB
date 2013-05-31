/*
 *  Copyright (c) 2012 Jan Kotek
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.mapdb;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * {@link Engine} wrapper which provides asynchronous serialization and asynchronous write.
 * This class takes an object instance, passes it to background writer thread (using Write Cache)
 * where it is serialized and written to disk. Async write does not affect commit durability,
 * write cache is flushed into disk on each commit. Modified records are held in small instance cache,
 * until they are written into disk.
 *
 * This feature is enabled by default and can be disabled by calling {@link DBMaker#asyncWriteDisable()}.
 * Write Cache is flushed in regular intervals or when it becomes full. Flush interval is 100 ms by default and
 * can be controlled by {@link DBMaker#asyncFlushDelay(int)}. Increasing this interval may improve performance
 * in scenarios where frequently modified items should be cached, typically {@link BTreeMap} import where keys
 * are presorted.
 *
 * Asynchronous write does not affect commit durability. Write Cache is flushed during each commit, rollback and close call.
 * You may also flush Write Cache manually by using {@link org.mapdb.AsyncWriteEngine#clearCache()}  method.
 * There is global lock which prevents record being updated while commit is in progress.
 *
 * This wrapper starts two threads named `MapDB writer #N` and `MapDB prealloc #N` (where N is static counter).
 * First thread is Async Writer, it takes modified records from Write Cache and writes them into store.
 * Second thread is Recid Preallocator, finding empty `recids` takes time so small stash is pre-allocated.
 * Those two threads are `daemon`, so they do not prevent JVM to exit.
 *
 * Asynchronous Writes have several advantages (especially for single threaded user). But there are two things
 * user should be aware of:
 *
 *  * Because data are serialized on back-ground thread, they need to be thread safe or better immutable.
 *    When you insert record into MapDB and modify it latter, this modification may happen before item
 *    was serialized and you may not be sure what version was persisted
 *
 *  * Asynchronous writes have some overhead and introduce single bottle-neck. This usually not issue for
 *    single or two threadsr, but in multi-threaded environment it may decrease performance.
 *    So in truly concurrent environments with many updates (network servers, parallel computing )
 *    you should disable Asynchronous Writes.
 *
 *
 * @see Engine
 * @see EngineWrapper
 *
 * @author Jan Kotek
 *
 *
 *
 */
public class AsyncWriteEngine extends EngineWrapper implements Engine {

    /** ensures thread name is followed by number */
    private static final AtomicLong threadCounter = new AtomicLong();


    /** used to signal that object was deleted*/
    protected static final Object TOMBSTONE = new Object();


    /** Queue of pre-allocated `recids`. Filled by `MapDB prealloc` thread
     * and consumed by {@link AsyncWriteEngine#put(Object, Serializer)} method  */
    protected final ArrayBlockingQueue<Long> newRecids = new ArrayBlockingQueue<Long>(CC.ASYNC_RECID_PREALLOC_QUEUE_SIZE);


    /** Associates `recid` from Write Queue with record data and serializer. */
    protected final LongConcurrentHashMap<Fun.Tuple2<Object, Serializer>> writeCache
            = new LongConcurrentHashMap<Fun.Tuple2<Object, Serializer>>();

    /** Each insert to Write Queue must hold read lock.
     *  Commit, rollback and close operations must hold write lock
     */
    protected final ReentrantReadWriteLock commitLock = new ReentrantReadWriteLock();

    /** number of active threads running, used to await thread termination on close */
    protected final CountDownLatch activeThreadsCount = new CountDownLatch(2);

    /** If background thread fails with exception, it is stored here, and rethrown to all callers.*/
    protected volatile Throwable threadFailedException = null;

    /** indicates that `close()` was called and background threads are being terminated*/
    protected volatile boolean closeInProgress = false;

    /** flush Write Queue every N milliseconds  */
    protected final int asyncFlushDelay;

    protected final AtomicReference<CountDownLatch> action = new AtomicReference<CountDownLatch>(null);


    /**
     * Construct new class and starts background threads.
     * User may provide executor in which background tasks will be executed,
     * otherwise MapDB starts two daemon threads.
     *
     * @param engine which stores data.
     * @param _asyncFlushDelay flush Write Queue every N milliseconds
     * @param executor optional executor to run tasks. If null daemon threads will be created
     */
    public AsyncWriteEngine(Engine engine, int _asyncFlushDelay,Executor executor) {
        super(engine);
        this.asyncFlushDelay = _asyncFlushDelay;
        startThreads(executor);
    }

    public AsyncWriteEngine(Engine engine) {
        this(engine, CC.ASYNC_WRITE_FLUSH_DELAY, null);
    }


    /**
     * Starts background threads.
     * You may override this if you wish to start thread different way
     *
     * @param executor optional executor to run tasks, if null deamon threads will be created
     */
    protected void startThreads(Executor executor) {
        //TODO background threads should exit, when `AsyncWriteEngine` was garbage-collected
        final Runnable preallocRun = new Runnable(){
            @Override public void run() {
                runPrealloc();
            }
        };

        final Runnable writerRun = new Runnable(){
            @Override public void run() {
                runWriter();
            }
        };

        if(executor!=null){
            executor.execute(preallocRun);
            executor.execute(writerRun);
            return;
        }
        final long threadNum = threadCounter.incrementAndGet();
        Thread prealloc = new Thread(preallocRun,"MapDB prealloc #"+threadNum);
        prealloc.setDaemon(true);
        prealloc.start();
        Thread writerThread = new Thread(writerRun,"MapDB writer #"+threadNum);
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /** runs on background thread, preallocates recids and puts them into Prealloc Queue */
    protected void runPrealloc() {
        try{
            for(;;){
                if(closeInProgress || threadFailedException !=null) return;
                Long newRecid = getWrappedEngine().put(Utils.EMPTY_STRING, Serializer.EMPTY_SERIALIZER);
                newRecids.put(newRecid);
            }
        } catch (Throwable e) {
            threadFailedException = e;
        }finally {
            activeThreadsCount.countDown();
        }
    }

    /** runs on background thread. Takes records from Write Queue, serializes and writes them.*/
    protected void runWriter() {
        try{
            for(;;){
                if(threadFailedException !=null) return; //other thread has failed, no reason to continue

                //if conditions are right, slow down writes a bit
                if(asyncFlushDelay!=0 ){
                    LockSupport.parkNanos(1000L * 1000L * asyncFlushDelay);
                }

                final CountDownLatch latch = action.getAndSet(null);

                do{
                    LongMap.LongMapIterator<Fun.Tuple2<Object, Serializer>> iter = writeCache.longMapIterator();
                    while(iter.moveToNext()){
                        //usual write
                        final long recid = iter.key();
                        Fun.Tuple2<Object, Serializer> item = iter.value();
                        if(item == null) continue; //item was already written
                        if(item.a==TOMBSTONE){
                            //item was not updated, but deleted
                            AsyncWriteEngine.super.delete(recid, item.b);
                        }else{
                            //call update as usual
                            AsyncWriteEngine.super.update(recid, item.a, item.b);
                        }
                        //record has been written to underlying Engine, so remove it from cache with CAS
                        writeCache.remove(recid, item);
                    }
                }while(latch!=null && !writeCache.isEmpty());


                //operations such as commit,close, compact or close needs to be executed in Writer Thread
                //for this case CountDownLatch is used, it also signals when operations has been completed
                //CountDownLatch is used as special case to signalise special operation
                if(latch!=null){
                    if(!writeCache.isEmpty()) throw new InternalError();

                    final long count = latch.getCount();
                    if(count == 0){ //close operation
                        return;
                    }else if(count == 1){ //commit operation
                        AsyncWriteEngine.super.commit();
                        latch.countDown();
                    }else if(count==2){ //rollback operation
                        AsyncWriteEngine.super.rollback();
                        newRecids.clear();
                        latch.countDown();
                        latch.countDown();
                    }else if(count==3){ //compact operation
                        AsyncWriteEngine.super.compact();
                        latch.countDown();
                        latch.countDown();
                        latch.countDown();
                    }else{throw new InternalError();}
                }
            }
        } catch (Throwable e) {
            threadFailedException = e;
        }finally {
            activeThreadsCount.countDown();
        }
    }


    /** checks that background threads are ready and throws exception if not */
    protected void checkState() {
        if(closeInProgress) throw new IllegalAccessError("db has been closed");
        if(threadFailedException !=null) throw new RuntimeException("Writer thread failed", threadFailedException);
    }

    /**
     * {@inheritDoc}
     *
     * Recids are managed by underlying Engine. Finding free or allocating new recids
     * may take some time, so for this reason recids are preallocated by Prealloc Thread
     * and stored in queue. This method just takes preallocated recid from queue with minimal
     * delay.
     *
     * Newly inserted records are not written synchronously, but forwarded to background Writer Thread via queue.
     *
     * ![async-put](async-put.png)
     *
     @uml async-put.png
     actor user
     participant "put method" as put
     participant "Prealloc thread" as prea
     participant "Writer Thread" as wri
     note over prea: has preallocated \n recids in queue
     activate put
     user -> put: User calls put method
     prea-> put: takes preallocated recid
     put -> wri: forward record into Write Queue
     put -> user: return recid to user
     deactivate put
     note over wri: eventually\n writes record\n before commit
     */
    @Override
    public <A> long put(A value, Serializer<A> serializer) {
        commitLock.readLock().lock();
        try{
            Long recid = newRecids.poll();
            if(recid==null)
                recid = super.put(Utils.EMPTY_STRING, Serializer.EMPTY_SERIALIZER);
            update(recid, value, serializer);
            return recid;
        }finally{
            commitLock.readLock().unlock();
        }

    }


    /**
     * {@inheritDoc}
     *
     * This method first looks up into Write Cache if record is not currently being written.
     * If not it continues as usually
     *
     *
     */
    @Override
    public <A> A get(long recid, Serializer<A> serializer) {
        commitLock.readLock().lock();
        try{
            checkState();
            Fun.Tuple2<Object,Serializer> item = writeCache.get(recid);
            if(item!=null){
                if(item.a == TOMBSTONE) return null;
                return (A) item.a;
            }

            return super.get(recid, serializer);
        }finally{
            commitLock.readLock().unlock();
        }
    }


    /**
     * {@inheritDoc}
     *
     * This methods forwards record into Writer Thread and returns asynchronously.
     *
     * ![async-update](async-update.png)
     * @uml async-update.png
     * actor user
     * participant "update method" as upd
     * participant "Writer Thread" as wri
     * activate upd
     * user -> upd: User calls update method
     * upd -> wri: forward record into Write Queue
     * upd -> user: returns
     * deactivate upd
     * note over wri: eventually\n writes record\n before commit
     */
    @Override
    public <A> void update(long recid, A value, Serializer<A> serializer) {
        if(serializer!=SerializerPojo.serializer) commitLock.readLock().lock();
        try{
            checkState();
            writeCache.put(recid, new Fun.Tuple2(value, serializer));
        }finally{
            if(serializer!=SerializerPojo.serializer) commitLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * This method first looks up Write Cache if record is not currently being written.
     * Successful modifications are forwarded to Write Thread and method returns asynchronously.
     * Asynchronicity does not affect atomicity.
     */
    @Override
    public <A> boolean compareAndSwap(long recid, A expectedOldValue, A newValue, Serializer<A> serializer) {
        commitLock.writeLock().lock();
        try{
            checkState();
            Fun.Tuple2<Object, Serializer> existing = writeCache.get(recid);
            A oldValue = existing!=null? (A) existing.a : super.get(recid, serializer);
            if(oldValue == expectedOldValue || (oldValue!=null && oldValue.equals(expectedOldValue))){
                writeCache.put(recid, new Fun.Tuple2(newValue, serializer));
                return true;
            }else{
                return false;
            }
        }finally{
            commitLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     *  This method places 'tombstone' into Write Queue so record is eventually
     *  deleted asynchronously. However record is visible as deleted immediately.
     */
    @Override
    public <A> void delete(long recid, Serializer<A> serializer) {
        update(recid, (A) TOMBSTONE, serializer);
    }

    /**
     * {@inheritDoc}
     *
     *  This method blocks until Write Queue is flushed and Writer Thread writes all records and finishes.
     *  When this method was called `closeInProgress` is set and no record can be modified.
     */
    @Override
    public void close() {
        commitLock.writeLock().lock();
        try {
            if(closeInProgress) return;
            checkState();
            closeInProgress = true;
            //notify background threads
            if(!action.compareAndSet(null,new CountDownLatch(0)))throw new InternalError();
            Long last = newRecids.take();
            if(last!=null)
                super.delete(last, Serializer.EMPTY_SERIALIZER);

            //wait for background threads to shutdown
            activeThreadsCount.await();

            //put preallocated recids back to store
            for(Long recid = newRecids.poll(); recid!=null; recid = newRecids.poll()){
                super.delete(recid, Serializer.EMPTY_SERIALIZER);
            }

            AsyncWriteEngine.super.close();

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            commitLock.writeLock().unlock();
        }
    }



    /**
     * {@inheritDoc}
     *
     *  This method blocks until Write Queue is flushed.
     *  All put/update/delete methods are blocked while commit is in progress (via global ReadWrite Commit Lock).
     *  After this method returns, commit lock is released and other operations may continue
     */
    @Override
    public void commit() {
        commitLock.writeLock().lock();
        try{
            checkState();
            //notify background threads
            CountDownLatch msg = new CountDownLatch(1);
            if(!action.compareAndSet(null,msg))throw new InternalError();

            //wait for response from writer thread
            while(!msg.await(1,TimeUnit.SECONDS)){
                checkState();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            commitLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     *  This method blocks until Write Queue is cleared.
     *  All put/update/delete methods are blocked while rollback is in progress (via global ReadWrite Commit Lock).
     *  After this method returns, commit lock is released and other operations may continue
     */
    @Override
    public void rollback() {
        commitLock.writeLock().lock();
        try{
            checkState();
            //notify background threads
            CountDownLatch msg = new CountDownLatch(2);
            if(!action.compareAndSet(null,msg))throw new InternalError();

            //wait for response from writer thread
            while(!msg.await(1,TimeUnit.SECONDS)){
                checkState();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            commitLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * This method blocks all put/update/delete operations until it finishes (via global ReadWrite Commit Lock).
     *
     */
    @Override
    public void compact() {
        commitLock.writeLock().lock();
        try{
            checkState();
            //notify background threads
            CountDownLatch msg = new CountDownLatch(3);
            if(!action.compareAndSet(null,msg))throw new InternalError();

            //wait for response from writer thread
            while(!msg.await(1,TimeUnit.SECONDS)){
                checkState();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            commitLock.writeLock().unlock();
        }
    }


    /**
     * {@inheritDoc}
     *
     *  This method blocks until Write Queue is empty (written into disk).
     *  It also blocks any put/update/delete operations until it finishes (via global ReadWrite Commit Lock).
     */
    @Override
    public void clearCache() {
        commitLock.writeLock().lock();
        try{
            checkState();
            //wait for response from writer thread
            while(!writeCache.isEmpty()){
                checkState();
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            commitLock.writeLock().unlock();
        }
        super.clearCache();
    }


}
