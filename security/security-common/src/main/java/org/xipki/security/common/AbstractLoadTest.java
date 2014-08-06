/*
 * Copyright (c) 2014 Lijun Liao
 *
 * TO-BE-DEFINE
 *
 */

package org.xipki.security.common;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Lijun Liao
 */

public abstract class AbstractLoadTest
{
    private static final String PROPKEY_LOADTEST = "org.xipki.loadtest";

    protected abstract Runnable getTestor()
    throws Exception;

    public void test()
    {
        System.getProperties().setProperty(PROPKEY_LOADTEST, "true");
        System.out.println("Testing using " + threads + " threads.");
        resetStartTime();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++)
        {
            Runnable runnable;
            try
            {
                runnable = getTestor();
            } catch (Exception e)
            {
                System.err.println("Cannot initialize Testor\nError message: " + e.getMessage());
                return;
            }

            executor.execute(runnable);
        }

        executor.shutdown();
        printHeader();
        while(true)
        {
            printStatus();
            try
            {
                boolean terminated = executor.awaitTermination(1, TimeUnit.SECONDS);
                if(terminated)
                {
                    break;
                }
            } catch (InterruptedException e)
            {
                break;
            }
        }

        printSummary();

        System.getProperties().remove(PROPKEY_LOADTEST);
    }

    private final ConcurrentLinkedDeque<MeasurePoint> measureDeque = new ConcurrentLinkedDeque<>();

    private static int DEFAULT_DURATION = 30; // 30 seconds
    private int duration = DEFAULT_DURATION; // in seconds
    public void setDuration(int duration)
    {
        if(duration > 0)
        {
            this.duration = duration;
        }
    }

    private static int DEFAULT_THREADS = 25;
    private int threads = DEFAULT_THREADS;
    public void setThreads(int threads)
    {
        if(threads > 0)
        {
            this.threads = threads;
        }
    }

    private AtomicInteger account = new AtomicInteger(0);
    private AtomicInteger errorAccount = new AtomicInteger(0);

    public int getErrorAccout()
    {
        return errorAccount.get();
    }

    protected void account(int all, int failed)
    {
        account.addAndGet(all);
        errorAccount.addAndGet(failed);
    }

    private long startTime = 0;
    protected void resetStartTime()
    {
        startTime = System.currentTimeMillis();
        measureDeque.add(new MeasurePoint(startTime, 0));
    }

    protected boolean stop()
    {
        return errorAccount.get() > 0 || System.currentTimeMillis() - startTime >= duration * 1000L;
    }

    protected static void printHeader()
    {
        System.out.println(" processed      time       #/s");
    }

    protected void printStatus()
    {
        int currentAccount = account.get();
        long now = System.currentTimeMillis();
        measureDeque.addLast(new MeasurePoint(now, currentAccount));

        String accountS = Integer.toString(currentAccount);
        StringBuilder sb = new StringBuilder("\r");

        // 10 characters for processed accout
        for (int i = 0; i < 10 -accountS.length(); i++)
        {
            sb.append(" ");
        }
        sb.append(currentAccount);

        long t = (now - startTime)/1000;  // in s
        String time = formatTime(t);
        sb.append("  ");
        sb.append(time);

        MeasurePoint referenceMeasurePoint;
        int numMeasurePoints = measureDeque.size();
        if(numMeasurePoints > 5)
        {
            referenceMeasurePoint = measureDeque.removeFirst();
        }
        else
        {
            referenceMeasurePoint = measureDeque.getFirst();
        }

        long t2inms = now - referenceMeasurePoint.measureTime; // in ms
        if(t2inms > 0)
        {
            long average = (currentAccount - referenceMeasurePoint.measureAccount) * 1000 / t2inms;

            String averageS = Long.toString(average);
            for (int i = 0; i < 10 -averageS.length(); i++)
            {
                sb.append(" ");
            }
            sb.append(average);
        }

        System.out.print(sb.toString());
        System.out.flush();
    }

    private String unit = "";
    public void setUnit(String unit)
    {
        if(unit != null)
        {
            this.unit = unit;
        }
    }
    protected void printSummary()
    {
        StringBuilder sb = new StringBuilder();
        long ms = (System.currentTimeMillis() - startTime);
        sb.append("\nFinished in " + ms/1000f + " s\n");
        sb.append("Account: " + account.get() + " " + unit + "\n");
        sb.append(" Failed: " + errorAccount.get() + " " + unit + "\n");
        sb.append("Average: " + (account.get() * 1000 / ms) + " " + unit + "/s\n");
        System.out.println(sb.toString());
    }

    private static String formatTime(long seconds)
    {
        long h = seconds / 3600;
        long m = (seconds - h * 3600) / 60;
        long s = seconds - h * 3600 - m * 60;

        StringBuilder sb = new StringBuilder();
        // hours
        if(h == 0)
        {
            sb.append("   ");
        }
        else if(h < 10)
        {
            sb.append(" " + h + ":");
        }
        else
        {
            sb.append(h + ":");
        }

        // minutes
        if(m < 10)
        {
            sb.append("0" + m + ":");
        }
        else
        {
            sb.append(m + ":");
        }

        // seconds
        if(s < 10)
        {
            sb.append("0" + s);
        }
        else
        {
            sb.append(s);
        }

        return sb.toString();
    }

    private static class MeasurePoint
    {
        private long measureTime;
        private int measureAccount;

        public MeasurePoint(long measureTime, int measureAccount)
        {
            this.measureTime = measureTime;
            this.measureAccount = measureAccount;
        }
    }
}
