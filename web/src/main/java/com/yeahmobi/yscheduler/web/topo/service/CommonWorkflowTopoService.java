package com.yeahmobi.yscheduler.web.topo.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.yeahmobi.yscheduler.common.Constants;
import com.yeahmobi.yscheduler.model.Task;
import com.yeahmobi.yscheduler.model.TaskInstance;
import com.yeahmobi.yscheduler.model.Team;
import com.yeahmobi.yscheduler.model.User;
import com.yeahmobi.yscheduler.model.WorkflowDetail;
import com.yeahmobi.yscheduler.model.WorkflowInstance;
import com.yeahmobi.yscheduler.model.service.TaskInstanceService;
import com.yeahmobi.yscheduler.model.service.TaskService;
import com.yeahmobi.yscheduler.model.service.TeamService;
import com.yeahmobi.yscheduler.model.service.UserService;
import com.yeahmobi.yscheduler.model.service.WorkflowDetailService;
import com.yeahmobi.yscheduler.model.service.WorkflowInstanceService;
import com.yeahmobi.yscheduler.web.controller.topo.TopoNode;
import com.yeahmobi.yscheduler.web.vo.WorkflowDetailVO;

@Service("commonTopo")
public class CommonWorkflowTopoService implements WorkflowTopoService {

    @Autowired
    private WorkflowInstanceService workflowInstanceService;

    @Autowired
    private WorkflowDetailService   detailService;

    @Autowired
    private TaskService             taskService;

    @Autowired
    private TaskInstanceService     taskInstanceService;

    @Autowired
    private UserService             userService;

    @Autowired
    private TeamService             teamService;

    private List<WorkflowDetailVO> getWorkflowDetail(long workflowId) {
        List<WorkflowDetailVO> raw = new ArrayList<WorkflowDetailVO>();
        List<WorkflowDetail> details = this.detailService.list(workflowId);

        for (WorkflowDetail detail : details) {
            WorkflowDetailVO detailVO = new WorkflowDetailVO();
            Long taskId = detail.getTaskId();
            List<Long> dependencies = this.detailService.listDependencyTaskIds(workflowId, taskId);
            detailVO.setWorkflowDetail(detail);
            detailVO.setDependencies(dependencies);
            Task task = this.taskService.get(taskId);
            Long teamId = this.userService.get(task.getOwner()).getTeamId();
            detailVO.setTaskName(task.getName());
            detailVO.setTeamId(teamId);
            raw.add(detailVO);
        }
        return raw;
    }

    private TopoNode buildTree(List<WorkflowDetailVO> raw) {
        TopoNode root = null;
        Map<Long, TopoNode> nodes = new HashMap<Long, TopoNode>();
        for (WorkflowDetailVO detail : raw) {
            Long taskId = detail.getWorkflowDetail().getTaskId();
            TopoNode node = new TopoNode();
            node.setName(detail.getTaskName());
            node.setTaskId(taskId);
            node.setTeamId(detail.getTeamId());
            nodes.put(taskId, node);
        }
        for (TopoNode node : nodes.values()) {
            node.setNodes(new ArrayList<TopoNode>());
        }
        for (WorkflowDetailVO detail : raw) {
            TopoNode node = nodes.get(detail.getWorkflowDetail().getTaskId());
            List<Long> dependencies = detail.getDependencies();
            // found root
            if ((dependencies == null) || (dependencies.size() == 0)) {
                root = node;
            }
            for (Long taskId : dependencies) {
                TopoNode denpendingNode = nodes.get(taskId);
                List<TopoNode> dependByNodes = denpendingNode.getNodes();
                dependByNodes.add(node);
            }
        }
        // revert

        if (root == null) {
            throw new IllegalStateException("Can not found root node, details=" + raw);
        }

        return root;
    }

    private void setStatus(TopoNode root, long workflowInstanceId) {
        List<TaskInstance> instances = this.taskInstanceService.listByWorkflowInstanceId(workflowInstanceId);
        if ((instances != null) && (instances.size() > 0)) {
            Map<Long, TopoNode> map = new HashMap<Long, TopoNode>();
            treeToMap(root, map);
            for (TaskInstance instance : instances) {
                long taskId = instance.getTaskId();
                TopoNode node = map.get(taskId);
                if (node != null) {
                    node.setStatus(instance.getStatus());
                }
            }
        }
    }

    private void treeToMap(TopoNode root, Map<Long, TopoNode> map) {
        map.put(root.getTaskId(), root);
        List<TopoNode> children = root.getNodes();
        if (children != null) {
            for (TopoNode child : children) {
                treeToMap(child, map);
            }
        }
    }

    public TopoNode buildWorkflowTopoTree(Long workflowId, List<WorkflowDetailVO> raw, boolean isAdmin, long userId) {
        if (!isAdmin) {
            List<WorkflowDetailVO> workflowDetails = getWorkflowDetail(workflowId);
            long teamId = this.userService.get(userId).getTeamId();
            for (WorkflowDetailVO detail : workflowDetails) {
                if ((detail.getTeamId() != teamId) || this.taskService.isRootTask(detail.getTaskName())) {
                    raw.add(detail);
                }
            }
        }
        TopoNode root = buildTree(raw);
        if (!isAdmin) {
            cutTree(userId, root);
        }
        return root;
    }

    public TopoNode buildWorkflowTopoTree(long workflowId, boolean isAdmin, long userId) {
        List<WorkflowDetailVO> raw = getWorkflowDetail(workflowId);
        TopoNode root = buildTree(raw);
        if (!isAdmin) {
            cutTree(userId, root);
        }

        return root;
    }

    /**
     * <pre>
     * 截枝，只保留以下节点：根节点(root)，当前team根节点(curTeamRoot)，team所属节点(curTeamNodes)，指向“curTeamNodes”的节点(relativeTeamNodes)，指向
     * "relativeTeamNodes"的team根节点(relativeTeamRoot))
     * 1. 算出当前team根节点(curTeamRoot)
     * 2. 从curTeamRoot开始往下找,找出curTeamNodes，过滤条件是teamId （做成set）
     * 3. 遍历其他team根节点，找出relativeTeamNodes（只要指向set，即符合，且停止往下）
     * 4.
     * </pre>
     * 
     * @param root
     */
    private void cutTree(long userId, TopoNode root) {
        // 最終被保留的node
        Map<TopoNode, Set<TopoNode>> reservedTeamNodeMap = new HashMap<TopoNode, Set<TopoNode>>();

        User user = this.userService.get(userId);
        Team team = this.teamService.get(user.getTeamId());
        String teamName = team.getName();
        Long teamId = team.getId();

        // 算出当前team根节点(curTeamRoot)
        TopoNode curTeamRoot = null;
        List<TopoNode> allTeamRoots = root.getNodes();
        for (TopoNode teamRoot : allTeamRoots) {
            String taskName = teamRoot.getName();
            if (taskName.equals(String.format(Constants.ROOT_NODE_PATTERN, teamName))) {
                curTeamRoot = teamRoot;
                break;
            }
        }

        // 从curTeamRoot开始往下找,找出curTeamNodes，过滤条件是teamId （做成set）
        Set<TopoNode> curTeamNodes = new HashSet<TopoNode>();
        putTeamNode(curTeamRoot, teamId, curTeamNodes);

        // 遍历其他team根节点，找出relativeTeamNodes（只要指向set，即符合，且停止往下）
        for (TopoNode teamRoot : allTeamRoots) {
            if (teamRoot != curTeamRoot) {
                String teamNameTmp = teamRoot.getName().substring(1,
                                                                  teamRoot.getName().length()
                                                                          - (Constants.ROOT_NODE).length());
                Long teamIdTmp = this.teamService.get(teamNameTmp).getId();
                Set<TopoNode> reservedRelativeNodes = new HashSet<TopoNode>();
                putRelativeTeamNodes(teamRoot.getNodes(), curTeamNodes, reservedRelativeNodes, teamIdTmp);
                if (reservedRelativeNodes.size() > 0) {
                    reservedTeamNodeMap.put(teamRoot, reservedRelativeNodes);
                }
            }
        }

        // 删除root的孩子，只剩curTeamRoot
        Iterator<TopoNode> iterator = allTeamRoots.iterator();
        while (iterator.hasNext()) {
            TopoNode teamRoot = iterator.next();
            if (teamRoot != curTeamRoot) {
                iterator.remove();
            }
        }
        // 把reservedTeamNodeMap添加进去
        for (Entry<TopoNode, Set<TopoNode>> entry : reservedTeamNodeMap.entrySet()) {
            TopoNode teamRoot = entry.getKey();
            root.getNodes().add(teamRoot);
            Set<TopoNode> chilren = entry.getValue();
            teamRoot.setNodes(new ArrayList<TopoNode>(chilren));
        }
    }

    private void putRelativeTeamNodes(List<TopoNode> nodes, Set<TopoNode> curTeamNodes, Set<TopoNode> reservedNodes,
                                      Long teamId) {
        if (nodes != null) {
            for (TopoNode node : nodes) {
                if (!this.taskService.isRootTask(node.getName()) && (node.getTeamId() != teamId)) {
                    continue;
                }
                // 如果node指向了curTeamNodes，則node屬於reservedNodes，而且虛線
                List<TopoNode> refers = isReferTo(node, curTeamNodes);
                if (refers.size() > 0) {
                    reservedNodes.add(node);
                }
                // node只保留指向refers，其他孩子不要了
                List<TopoNode> childrenWithoutRefers = node.getNodes();
                childrenWithoutRefers.removeAll(refers);
                node.setNodes(refers);

                // 继续递归该node往下
                putRelativeTeamNodes(childrenWithoutRefers, curTeamNodes, reservedNodes, teamId);
            }
        }

    }

    private List<TopoNode> isReferTo(TopoNode node, Set<TopoNode> curTeamNodes) {
        List<TopoNode> refers = new ArrayList<TopoNode>();
        List<TopoNode> children = node.getNodes();
        if (children != null) {
            for (TopoNode child : children) {
                if (curTeamNodes.contains(child)) {
                    refers.add(child);
                }
            }
        }
        return refers;
    }

    private void putTeamNode(TopoNode curTeamRoot, Long teamId, Set<TopoNode> curTeamNodeTaskIds) {
        List<TopoNode> children = curTeamRoot.getNodes();
        if (children != null) {
            Iterator<TopoNode> iterator = children.iterator();
            while (iterator.hasNext()) {
                TopoNode node = iterator.next();
                if (node.getTeamId() == teamId) {
                    curTeamNodeTaskIds.add(node);
                    putTeamNode(node, teamId, curTeamNodeTaskIds);
                } else {
                    // 清调本team节点指向外部的线。
                    iterator.remove();
                }
            }
        }
    }

    public TopoNode buildInstanceTopoTree(long workflowInstanceId, boolean isAdmin, long userId) {
        WorkflowInstance workflowInstance = this.workflowInstanceService.get(workflowInstanceId);
        List<WorkflowDetailVO> raw = getWorkflowDetail(workflowInstance.getWorkflowId());
        TopoNode root = buildTree(raw);
        if (!isAdmin) {
            cutTree(userId, root);
        }
        setStatus(root, workflowInstanceId);
        return root;
    }
}
