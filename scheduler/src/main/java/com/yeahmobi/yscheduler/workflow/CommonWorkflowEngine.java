package com.yeahmobi.yscheduler.workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.base.Function;
import com.google.common.collect.Maps;
import com.yeahmobi.yscheduler.common.Constants;
import com.yeahmobi.yscheduler.model.TaskInstance;
import com.yeahmobi.yscheduler.model.Team;
import com.yeahmobi.yscheduler.model.Workflow;
import com.yeahmobi.yscheduler.model.WorkflowInstance;
import com.yeahmobi.yscheduler.model.service.TeamService;
import com.yeahmobi.yscheduler.model.service.TeamWorkflowStatusInstanceService;
import com.yeahmobi.yscheduler.model.type.TaskInstanceStatus;
import com.yeahmobi.yscheduler.model.type.WorkflowInstanceStatus;

/**
 * @author Leo Liang
 */
@Service("common")
public class CommonWorkflowEngine extends AbstractWorkflowEngine {

    private static final long                   CHECK_INTERVAL = 1000 * 5;

    @Autowired
    protected TeamWorkflowStatusInstanceService teamWorkflowInstanceStatusService;

    @Autowired
    private TeamService                         teamService;

    @Override
    protected List<WorkflowInstance> getAllRunningWorkflowInstances() {
        return this.workflowInstanceService.getAllRunning(true);
    }

    @Override
    protected String getName() {
        return "CommonWorkflowEngine";
    }

    @Override
    protected long getCheckIntervalMilliseconds() {
        return CHECK_INTERVAL;
    }

    @Override
    @PostConstruct
    public void init() {
        super.init();
        for (Map.Entry<Long, Pair> runningWorkflowEntry : this.runningWorkflows.entrySet()) {
            Long workflowInstanceId = runningWorkflowEntry.getKey();
            Map<Long, TaskInstanceWithDependency> taskInstanceWdMap = runningWorkflowEntry.getValue().taskInstances;

            Collection<TaskInstanceWithDependency> taskInstanceWithDependencies = taskInstanceWdMap.values();

            Collection<TaskInstanceWithDependency> taskInstanceWithDependenciesChanged = new ArrayList<TaskInstanceWithDependency>();
            if (taskInstanceWithDependencies != null) {
                for (TaskInstanceWithDependency instance : taskInstanceWithDependencies) {
                    // 和fetchLatestStatus逻辑一样，排除DEPENDENCY_WAIT
                    if (instance.getTaskInstance().getStatus() != TaskInstanceStatus.DEPENDENCY_WAIT) {
                        taskInstanceWithDependenciesChanged.add(instance);
                    }
                }
            }

            // 处理team的状态。
            updateTeamStatus(workflowInstanceId, taskInstanceWdMap, taskInstanceWithDependenciesChanged);
        }
    }

    @Override
    protected void checkAndExecute(Map.Entry<Long, Pair> runningWorkflowEntry) {

        Long workflowInstanceId = runningWorkflowEntry.getKey();
        Workflow workflow = runningWorkflowEntry.getValue().workflow;
        Map<Long, TaskInstanceWithDependency> taskInstanceWdMap = runningWorkflowEntry.getValue().taskInstances;
        WorkflowInstance workflowInstance = CommonWorkflowEngine.this.workflowInstanceService.get(workflowInstanceId);
        if (workflowInstance == null) {
            return;
        }

        Collection<TaskInstanceWithDependency> taskInstanceWithDependencies = taskInstanceWdMap.values();

        List<TaskInstanceWithDependency> taskInstancesStatusChanged = fetchLatestStatus(taskInstanceWithDependencies);

        // 处理状态变化的team的节点。
        if (taskInstancesStatusChanged.size() > 0) {
            updateTeamStatus(workflowInstanceId, taskInstanceWdMap, taskInstancesStatusChanged);
        }

        boolean cancelled = handleCancelIfNeeded(workflowInstanceId, taskInstanceWithDependencies);

        if (isAllTasksCompleted(workflowInstanceId, taskInstanceWithDependencies)) {
            workflowSuccess(workflowInstanceId);
        } else {
            if (!cancelled) {
                // 判断依赖是否满足
                for (TaskInstanceWithDependency taskInstanceWd : taskInstanceWdMap.values()) {
                    submitToTaskInstanceExecutorIfConditionSatisfy(workflow, workflowInstance,
                                                                   taskInstanceWithDependencies, taskInstanceWd);
                }
            }
        }

    }

    private void updateTeamStatus(Long workflowInstanceId, Map<Long, TaskInstanceWithDependency> taskInstanceWdMap,
                                  Collection<TaskInstanceWithDependency> taskInstancesStatusChanged) {
        Map<Long, WorkflowInstanceStatus> teamIdToWorkflowInstanceStatusMap = calStatus(taskInstanceWdMap,
                                                                                        taskInstancesStatusChanged);
        for (Map.Entry<Long, WorkflowInstanceStatus> entry : teamIdToWorkflowInstanceStatusMap.entrySet()) {
            long teamId = entry.getKey();
            WorkflowInstanceStatus status = entry.getValue();
            this.teamWorkflowInstanceStatusService.updateStatus(teamId, workflowInstanceId, status);
        }
    }

    private Map<Long, WorkflowInstanceStatus> calStatus(Map<Long, TaskInstanceWithDependency> taskInstanceWdMap,
                                                        Collection<TaskInstanceWithDependency> taskInstancesStatusChanged) {
        Set<Long> teamIds = new HashSet<Long>();
        for (TaskInstanceWithDependency taskInstanceWithDependency : taskInstancesStatusChanged) {
            Long teamId = getRealTeamId(taskInstanceWithDependency);

            if (teamId != null) {
                teamIds.add(teamId);
            }
        }
        Map<Long, List<TaskInstance>> teamIdToTaskInstancesMap = new HashMap<Long, List<TaskInstance>>();
        for (TaskInstanceWithDependency taskInstanceWd : taskInstanceWdMap.values()) {
            Long teamId = getRealTeamId(taskInstanceWd);
            if (teamId == null) {
                continue;
            }
            if (teamIds.contains(teamId)) {
                if (!teamIdToTaskInstancesMap.containsKey(teamId)) {
                    teamIdToTaskInstancesMap.put(teamId, new ArrayList<TaskInstance>());
                }
                teamIdToTaskInstancesMap.get(teamId).add(taskInstanceWd.getTaskInstance());
            }
        }
        return Maps.transformValues(teamIdToTaskInstancesMap,
                                    new Function<List<TaskInstance>, WorkflowInstanceStatus>() {

                                        public WorkflowInstanceStatus apply(List<TaskInstance> taskInstances) {
                                            return calStatusByTaskInstance(taskInstances);
                                        }
                                    });

    }

    private Long getRealTeamId(TaskInstanceWithDependency taskInstanceWithDependency) {
        Long teamId = null;
        List<Long> dependencies = taskInstanceWithDependency.getDependencies();
        String taskName = taskInstanceWithDependency.getTask().getName();
        boolean isTeamRoot = (dependencies.size() == 1) && this.taskService.isRootTask(taskName);
        if (isTeamRoot) {
            Team team = this.teamService.get(taskName.substring(1, taskName.length() - Constants.ROOT_NODE.length()));
            teamId = team.getId();
        } else {
            teamId = taskInstanceWithDependency.getTeamId();
        }
        return teamId;
    }

    private WorkflowInstanceStatus calStatusByTaskInstance(List<TaskInstance> taskInstances) {
        WorkflowInstanceStatus workflowInstanceStatus = null;

        int statusMark = 0;
        for (TaskInstance instance : taskInstances) {
            TaskInstanceStatus status = instance.getStatus();
            if (!status.isCompleted()) {
                statusMark |= (1 << 3);
                workflowInstanceStatus = WorkflowInstanceStatus.RUNNING;
                break;
            } else if (status == TaskInstanceStatus.FAILED) {
                statusMark |= (1 << 2);
            } else if (status == TaskInstanceStatus.CANCELLED) {
                statusMark |= (1 << 1);
            } else if (status == TaskInstanceStatus.SUCCESS) {
                statusMark |= 1;
            } else {// 其他状态也当作是fail
                statusMark |= (1 << 2);
            }
        }
        if (workflowInstanceStatus == null) {
            int m = getLeftStatusMark(statusMark);
            switch (m) {
                case 4:
                    workflowInstanceStatus = WorkflowInstanceStatus.RUNNING;
                    break;
                case 3:
                    workflowInstanceStatus = WorkflowInstanceStatus.FAILED;
                    break;
                case 2:
                    workflowInstanceStatus = WorkflowInstanceStatus.CANCELLED;
                    break;
                case 1:
                    workflowInstanceStatus = WorkflowInstanceStatus.SUCCESS;
                    break;
            }
        }

        return workflowInstanceStatus;
    }

    /**
     * 得到statusMark的最左1的位置，位置從左数起。
     */
    private static int getLeftStatusMark(int statusMark) {
        int i = 1;
        while ((statusMark >>> i) != 0)
            i++;
        return i;
    }
}
