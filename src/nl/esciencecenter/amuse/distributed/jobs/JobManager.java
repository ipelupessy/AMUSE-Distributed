/*
 * Copyright 2013 Netherlands eScience Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.esciencecenter.amuse.distributed.jobs;

import ibis.ipl.Ibis;
import ibis.ipl.IbisCreationFailedException;
import ibis.ipl.IbisFactory;
import ibis.ipl.MessageUpcall;
import ibis.ipl.ReadMessage;
import ibis.ipl.ReceivePort;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import nl.esciencecenter.amuse.distributed.DistributedAmuse;
import nl.esciencecenter.amuse.distributed.DistributedAmuseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Niels Drost
 */
public class JobManager extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(JobManager.class);

    public static final String PORT_NAME = "job.manager";

    private final Ibis ibis;

    private final PilotNodes nodes;

    //all pending jobs.
    private final LinkedList<Job> queue;

    private final List<WorkerJob> workers;
    private final List<ScriptJob> scriptJobs;
    private final List<PickledJob> pickledJobs;

    public JobManager(String serverAddress, File tmpDir) throws DistributedAmuseException {
        nodes = new PilotNodes(this);
        workers = new ArrayList<WorkerJob>();
        scriptJobs = new ArrayList<ScriptJob>();
        pickledJobs = new ArrayList<PickledJob>();

        try {
            Properties properties = new Properties();
            properties.put("ibis.server.address", serverAddress);
            properties.put("ibis.pool.name", "amuse");
            properties.put("ibis.location", "daemon@local");
            //properties.put("ibis.managementclient", "true");
            //properties.put("ibis.bytescount", "true");

            ibis = IbisFactory.createIbis(DistributedAmuse.IPL_CAPABILITIES, properties, true, nodes,
                    DistributedAmuse.ONE_TO_ONE_PORT_TYPE, DistributedAmuse.MANY_TO_ONE_PORT_TYPE);

            //label this ibis as the master node by running an election with us as the only 
            ibis.registry().elect("amuse");

            ibis.registry().enableEvents();

        } catch (IOException | IbisCreationFailedException e) {
            throw new DistributedAmuseException("failed to create ibis", e);
        }

        queue = new LinkedList<Job>();

        //start a thread to run the scheduling
        setName("Job Manager");
        setDaemon(true);
        start();
    }

    public Ibis getIbis() {
        return ibis;
    }

    private synchronized void addWorkerJob(WorkerJob job) {
        queue.add(job);
        
        workers.add(job);

        //run scheduler thread now
        notifyAll();
    }

    public PilotNodes getNodes() {
        return nodes;
    }
    
    public synchronized WorkerJob[] getWorkerJobs() {
        return workers.toArray(new WorkerJob[0]);
    }
    
    public synchronized ScriptJob[] getScriptJobs() {
        return scriptJobs.toArray(new ScriptJob[0]);
    }
    
    public synchronized PickledJob[] getPickledJobs() {
        return pickledJobs.toArray(new PickledJob[0]);
    }

    public synchronized Job getJob(int jobID) throws DistributedAmuseException {
        for(Job job: workers) {
            if (jobID == job.getJobID()) {
                return job;
            }
        }
        
        for(Job job: pickledJobs) {
            if (jobID == job.getJobID()) {
                return job;
            }
        }
        
        for(Job job: scriptJobs) {
            if (jobID == job.getJobID()) {
                return job;
            }
        }

        throw new DistributedAmuseException("Unknown job: " + jobID);
    }

    

    public WorkerJob submitWorkerJob(WorkerDescription jobDescription) throws DistributedAmuseException {
        WorkerJob result = new WorkerJob(jobDescription, ibis);

        addWorkerJob(result);

        return result;
    }

    private synchronized boolean allScriptJobsDone() {
        for (ScriptJob job : scriptJobs) {
            if (!job.isDone()) {
                return false;
            }
        }
        return true;
    }

    public synchronized void waitForScriptJobs() throws DistributedAmuseException {
        while (!allScriptJobsDone()) {
            try {
                wait();
            } catch (InterruptedException e) {
                throw new DistributedAmuseException("Interrupted while waiting for all jobs to finish");
            }
        }
    }

    public void end() {
        this.interrupt();

        for (Job job : getWorkerJobs()) {
            try {
                job.cancel();
            } catch (DistributedAmuseException e) {
                logger.error("Failed to cancel job: " + job, e);
            }
        }
        for (Job job : getScriptJobs()) {
            try {
                job.cancel();
            } catch (DistributedAmuseException e) {
                logger.error("Failed to cancel job: " + job, e);
            }
        }
        for (Job job : getPickledJobs()) {
            try {
                job.cancel();
            } catch (DistributedAmuseException e) {
                logger.error("Failed to cancel job: " + job, e);
            }
        }


        try {
            logger.debug("Terminating ibis pool");
            ibis.registry().terminate();
        } catch (IOException e) {
            logger.error("Failed to terminate ibis pool", e);
        }
        try {
            ibis.end();
        } catch (IOException e) {
            logger.error("Failed to end ibis", e);
        }
    }

    /**
     * Wake up the scheduler thread.
     */
    public synchronized void nudge() {
        notifyAll();
    }

    /**
     * Scheduler thread. Periodically checks if suitable nodes can be found for jobs.
     */
    @Override
    public synchronized void run() {
        while (true) {
            //find nodes for jobs to run on
            Iterator<Job> iterator = queue.iterator();
            while (iterator.hasNext()) {
                Job job = iterator.next();

                if (job.isPending()) {
                    //find nodes to run this job on. Usually only a single node, but worker jobs may require multiple nodes.
                    PilotNode[] target = nodes.getSuitableNodes(job);

                    //If suitable nodes are found
                    if (target != null) {
                        job.start(target);
                        //remove this job from the queue
                        iterator.remove();
                    }
                } else {
                    //remove this job from the queue
                    iterator.remove();
                }

            }

            try {
                wait(5000);
            } catch (InterruptedException e) {
                logger.debug("Scheduler thread interrupted, time to quit");
                return;
            }
        }
    }

}
