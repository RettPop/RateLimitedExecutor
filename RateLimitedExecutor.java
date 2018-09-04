    private static class RequestsGenerator implements Runnable
    {
        private final Logger LOG = LoggerFactory.getLogger(new Object()
        {
        }.getClass().getEnclosingClass());

        private double myQPS;
        private final Iterable<SearchRequest> myRequests;
        private final Collection<TestCaseState> myTCStates;
        private final AtomicLong mySentCounter;
        private final AtomicBoolean myStopFlag;
        private Integer myQpsIncrement;
        private long myQpsIncPeriod;
        ExecutorService myFutureCreator = Executors.newFixedThreadPool(8);
        ExecutorService myGeneratorExecutor = Executors.newSingleThreadExecutor();
        private long myExpectedOpDur;
        private final Queue<Statement> myStatementsQueue = new ConcurrentLinkedQueue<>();
        private final SearchStatementsGenerator myGenerator = new SearchStatementsGenerator();

        public RequestsGenerator(int qps, Iterable<SearchRequest> requests, Collection<TestCaseState> queue, AtomicLong sentCounter, AtomicBoolean stopFlag, Integer qpsIncrement, long qpsIncFrequency, TimeUnit freqUnit)
        {
            myQPS = qps;
            myRequests = Iterables.cycle(requests); //will iterate by circle
            myTCStates = queue;
            mySentCounter = sentCounter;
            myStopFlag = stopFlag;
            myQpsIncrement = qpsIncrement;
            myQpsIncPeriod = freqUnit.toNanos(qpsIncFrequency);
            myExpectedOpDur = (long) (TimeUnit.SECONDS.toNanos(1) / myQPS);

            // preparing statements queue
            myGenerator.fillQueueWithStatements(myStatementsQueue, (int)myQPS*2, myRequests);
        }

        @Override
        public void run()
        {
            LOG.debug("Generator is launched");

            long opFinish = 0;
            long lastQPSSetTime = System.nanoTime();
            while (!myStopFlag.get())
            {
                long opStart = System.nanoTime();
                long finalOpFinish = opFinish;
                class FutureListener implements Runnable
                {
                    TestCaseState myTC;

                    public FutureListener(TestCaseState testCaseState)
                    {
                        myTC = testCaseState;
                    }

                    @Override
                    public void run()
                    {
                        myTC.finish();
                    }
                }

                // if requests queue is empty, wait for it's filling up
                if(myStatementsQueue.isEmpty())
                {
                    LOG.debug("Requests queue is empty. Wait for filling");
                    myGenerator.fillQueueWithStatements(myStatementsQueue, (int)myQPS * 2, myRequests);
                }
                else if(myStatementsQueue.size() < (int)myQPS)
                {
                    //... otherwise fill it in separate thread
                    LOG.debug("Requests queue is almost empty. Ask to fill it");
                    myGeneratorExecutor.submit(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            myGenerator.fillQueueWithStatements(myStatementsQueue, (int)myQPS * 2, myRequests);
                        }
                    });
                }

                // produce new future
                myFutureCreator.execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        long start = opStart;
                        long lastOp = finalOpFinish;
                        try
                        {
                            Statement st = myStatementsQueue.poll();
                            TestCaseState tcs = TestCaseState.builder()
                                    .name(st.toString())
                                    .build();

                            ResultSetFuture future = CilUtil.cilCluster()
                                    .getPersistenceHandler()
                                    .executeAsyncPreparedStatement(st, null);

                            tcs.setCustomObject(future);
                            future.addListener(new FutureListener(tcs), MoreExecutors.sameThreadExecutor());

                            myTCStates.add(tcs);
                            mySentCounter.incrementAndGet();
                            LOG.debug("Request is sent {}ms since last with statement: {}", TimeUnit.NANOSECONDS.toMillis(start - lastOp), st.toString());
                        }
                        catch (UnableToProcessException e)
                        {
                            LOG.error("Error creating future", e);
                        }
                    }
                });

                opFinish = System.nanoTime();

                //check if we need to increment QPS
                if (myQpsIncrement != 0)
                {
                    if (opFinish - lastQPSSetTime > myQpsIncPeriod)
                    {
                        myQPS += myQpsIncrement;
                        LOG.debug("Changing QPS to {}", myQPS);
                        if (myQPS <= 0)
                        {
                            LOG.info("QPS became 0. Exit");
                            return;
                        }
                        myExpectedOpDur = (long) (TimeUnit.SECONDS.toNanos(1) / myQPS);
                        lastQPSSetTime = opFinish;
                    }
                }

                long opDur = opFinish - opStart; // operation duration.
                int sleepTime = (int) (myExpectedOpDur - opDur); // nanos
                int millis = (int) (sleepTime / TimeUnit.MILLISECONDS.toNanos(1));
                int nanos = sleepTime - (int) (millis * TimeUnit.MILLISECONDS.toNanos(1));
                if (millis >= 0 && nanos >= 0)
                {
                    try
                    {
                        LOG.debug("Sleep for {} millis, {} nanos before next request", millis, nanos);
                        Thread.sleep(millis, nanos);
                    }
                    catch (Exception e)
                    {
                        LOG.debug("Error while trying to sleep: operation took {}. Sleep for {} ms {} ns", opDur, millis, nanos);
                        LOG.error("Error sleeping", e);
                    }
                }
            }

            LOG.debug("Generator is finished");
        }
    }
}
