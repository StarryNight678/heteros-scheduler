/*******************************************************************************
 * Copyright (c) 2013 Leonardo Aniello, Roberto Baldoni, Leonardo Querzoni.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Leonardo Aniello, Roberto Baldoni, Leonardo Querzoni
 *******************************************************************************/
package edu.fudan.storm.LoadMonitor;

import edu.fudan.storm.config.MonitorConfiguration;
import edu.fudan.storm.units.Executor;
import edu.fudan.storm.utils.RedisUtil;
import edu.fudan.storm.utils.Stootils;
import org.apache.log4j.Logger;
import org.apache.storm.task.TopologyContext;
import redis.clients.jedis.Jedis;

import java.util.*;


public class WorkerMonitor {

    private static WorkerMonitor instance = null;

    private String topologyId;
    private int workerPort;
    private Logger logger;

    /*
     * threadId -> time series of the load
     */
    private Map<Long, List<Long>> loadStats;

    /*
     * threadId -> list of tasks Id, in the form [begin task, end task] = Executor
     */
    private Map<Long, Executor> threadToTaskMap;

    private List<TaskMonitor> taskMonitorList;

    private Map<String, String> taskToComponentMap;

    private int timeWindowSlotCount;

    private int timeWindowSlotLength;

    private Jedis jedisClient;


    public synchronized static WorkerMonitor getInstance() {
        if (instance == null)
            instance = new WorkerMonitor();
        return instance;
    }

    private WorkerMonitor() {
        logger = Logger.getLogger(WorkerMonitor.class);
        loadStats = new HashMap<Long, List<Long>>();
        threadToTaskMap = new HashMap<Long, Executor>();
        taskMonitorList = new ArrayList<TaskMonitor>();
        taskToComponentMap = new HashMap<>();

        timeWindowSlotCount = MonitorConfiguration.getInstance().getTimeWindowSlotCount();
        timeWindowSlotLength = MonitorConfiguration.getInstance().getTimeWindowSlotLength();

        //get jedis client instance
        jedisClient = RedisUtil.getInstance();

        new WorkerMonitorThread().start();
        logger.info("WorkerMonitor started!!");
    }

    /*
     * made once by each task in its nextTuple() or execute() method
     */
    public synchronized void registerTask(TaskMonitor taskMonitor) {
        Executor executor = threadToTaskMap.get(taskMonitor.getThreadId());
        if (executor == null) {
            executor = new Executor();
            threadToTaskMap.put(taskMonitor.getThreadId(), executor);
        }
        if (!executor.includes(taskMonitor.getTaskId()))
            executor.add(taskMonitor.getTaskId());
        taskMonitorList.add(taskMonitor);
    }

      public synchronized void sampleStats() {
        // load
        Map<Long, Long> loadInfo = LoadMonitor.getInstance().getLoadInfo(threadToTaskMap.keySet());
        for (long threadId : loadInfo.keySet())
            notifyLoadStat(threadId, loadInfo.get(threadId));
    }

    /**
     * @param threadID
     * @return average CPU cycles per second consumed by threadID
     */
    private long getLoad(long threadID) {
        long total = 0;
        List<Long> loadData = loadStats.get(threadID);
        for (long load : loadData)
            total += load;
        return total / (loadData.size() * timeWindowSlotLength);
    }

    public void storeStats(){

        logger.debug("WorkerMonitor Snapshot");
        logger.debug("----------------------------------------");
        logger.debug("Topology ID: " + topologyId);
        logger.debug("Worker Port: " + workerPort);
        logger.debug("Threads to Tasks association:");

        for (long threadId : threadToTaskMap.keySet()) {
            logger.debug("- " + threadId + ": " + threadToTaskMap.get(threadId));
        }

        logger.debug("Load Stats (CPU cycles consumed per time slot):");

        long totalCPUCyclesPerSecond = 0;
        for (long threadId : loadStats.keySet()) {
            List<Long> threadLoadInfo = loadStats.get(threadId);
            totalCPUCyclesPerSecond += threadLoadInfo.get(threadLoadInfo.size() - 1) / timeWindowSlotLength;
            logger.debug("- thread " + threadId + ": " + getLoad(threadId) + " Mcycle/s [" + Stootils.collectionToString(threadLoadInfo) + "]");
            Executor executor = threadToTaskMap.get(threadId);
            executor.setLoad(getLoad(threadId));
        }

        //store task to component map to redis
        String redis_task_to_component = RedisUtil.getTaskToComponentMap(topologyId);
        jedisClient.hmset(redis_task_to_component, taskToComponentMap);

        //store load stats into redis
        String redis_cpu_load_map = RedisUtil.getTaskCPULoadMap(topologyId);
        Map<String, String> task_load = new HashMap();
        for (Long Tid : loadStats.keySet()) {
            String taskId = String.valueOf(threadToTaskMap.get(Tid).getBeginTask());
            String taskLoad = String.valueOf(getLoad(Tid));
            task_load.put(taskId, taskLoad);
            logger.debug("Topology: " + topologyId + " component: "+taskToComponentMap.get(threadToTaskMap.get(Tid).getBeginTask())+" Task: " + taskId + " Load: " + taskLoad);
            logger.debug("Debug flag ---------------------------------------------------------");
        }
        try {
            String message = jedisClient.hmset(redis_cpu_load_map, task_load);
            logger.info("Redis return value: " + message);
            Iterator<String> iter = jedisClient.hkeys(redis_cpu_load_map).iterator();
            while (iter.hasNext()) {
                String key = iter.next();
                logger.info(key + ":" + jedisClient.hmget(redis_cpu_load_map, key));
            }
            long totalCPUCyclesAvailable = CPUInfo.getInstance().getTotalSpeed();
            int usage = (int) (((double) totalCPUCyclesPerSecond / totalCPUCyclesAvailable) * 100);
            logger.debug("Total CPU cycles consumed per second: " + totalCPUCyclesPerSecond + ", Total available: " + totalCPUCyclesAvailable + ", Usage: " + usage + "%");
        }catch (Exception e){
            logger.error(e.getMessage());
            e.printStackTrace();
        }
    }

    private void notifyLoadStat(long threadId, long load) {
        List<Long> loadList = loadStats.get(threadId);
        if (loadList == null) {
            loadList = new ArrayList<Long>();
            loadStats.put(threadId, loadList);
        }
        loadList.add(load);
        if (loadList.size() > timeWindowSlotCount)
            loadList.remove(0);
    }

    public String getTopologyId() {
        return topologyId;
    }

    public int getWorkerPort() {
        return workerPort;
    }

    public void setContextInfo(TopologyContext context) {
        this.topologyId = context.getStormId();
        this.workerPort = context.getThisWorkerPort();
        this.logger.debug("add task "+context.getThisTaskId()+" to component "+context.getThisComponentId());
        this.taskToComponentMap.put(String.valueOf(context.getThisTaskId()),context.getThisComponentId());
    }
}
