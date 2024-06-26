package com.warm.flow.core.service.impl;

import com.warm.flow.core.FlowFactory;
import com.warm.flow.core.constant.ExceptionCons;
import com.warm.flow.core.constant.FlowCons;
import com.warm.flow.core.dao.FlowTaskDao;
import com.warm.flow.core.dto.FlowParams;
import com.warm.flow.core.entity.*;
import com.warm.flow.core.enums.FlowStatus;
import com.warm.flow.core.enums.NodeType;
import com.warm.flow.core.enums.SkipType;
import com.warm.flow.core.listener.Listener;
import com.warm.flow.core.listener.ListenerVariable;
import com.warm.flow.core.orm.service.impl.WarmServiceImpl;
import com.warm.flow.core.service.TaskService;
import com.warm.flow.core.utils.AssertUtil;
import com.warm.flow.core.utils.ExpressionUtil;
import com.warm.flow.core.utils.ListenerUtil;
import com.warm.flow.core.utils.SqlHelper;
import com.warm.tools.utils.*;
import org.noear.snack.ONode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 待办任务Service业务层处理
 *
 * @author warm
 * @date 2023-03-29
 */
public class TaskServiceImpl extends WarmServiceImpl<FlowTaskDao<Task>, Task> implements TaskService {

    @Override
    public TaskService setDao(FlowTaskDao<Task> warmDao) {
        this.warmDao = warmDao;
        return this;
    }

    @Override
    public Instance skip(Long taskId, FlowParams flowParams) {
        AssertUtil.isTrue(StringUtils.isNotEmpty(flowParams.getMessage())
                && flowParams.getMessage().length() > 500, ExceptionCons.MSG_OVER_LENGTH);
        // 获取待办任务
        Task task = getById(taskId);
        AssertUtil.isTrue(ObjectUtil.isNull(task), ExceptionCons.NOT_FOUNT_TASK);
        // 获取当前流程
        Instance instance = FlowFactory.insService().getById(task.getInstanceId());
        AssertUtil.isTrue(ObjectUtil.isNull(instance), ExceptionCons.NOT_FOUNT_INSTANCE);
        return skip(flowParams, task, instance);
    }

    @Override
    public Instance skip(FlowParams flowParams, Task task, Instance instance) {
        // TODO min 后续考虑并发问题，待办任务和实例表不同步，可给代办任务id加锁，抽取所接口，方便后续兼容分布式锁
        // 非第一个记得跳转类型必传
        if (!NodeType.isStart(task.getNodeType())) {
            AssertUtil.isFalse(StringUtils.isNotEmpty(flowParams.getSkipType()), ExceptionCons.NULL_CONDITIONVALUE);
        }

        Node NowNode = CollUtil.getOne(FlowFactory.nodeService()
                .getByNodeCodes(Collections.singletonList(task.getNodeCode()), task.getDefinitionId()));

        //执行开始节点 开始监听器
        ListenerUtil.executeListener(new ListenerVariable(instance, NowNode, flowParams.getVariable(), task)
                , Listener.LISTENER_START);

        //判断结点是否有权限监听器,有执行权限监听器NowNode.setPermissionFlag,无走数据库的权限标识符
        ListenerUtil.executeGetNodePermission(new ListenerVariable(instance, NowNode, flowParams.getVariable(), task));

        // 获取关联的节点
        Node nextNode = getNextNode(NowNode, task, flowParams);

        // 如果是网关节点，则重新获取后续节点
        List<Node> nextNodes = getNextByCheckGateWay(flowParams, nextNode);

        //判断下一结点是否有权限监听器,有执行权限监听器nextNode.setPermissionFlag,无走数据库的权限标识符
        ListenerUtil.executeGetNodePermission(new ListenerVariable(instance, flowParams.getVariable(), task)
                , StreamUtils.toArray(nextNodes, Node[]::new));

        // 构建增代办任务和设置结束任务历史记录
        List<Task> addTasks = buildAddTasks(flowParams, task, instance, nextNodes, nextNode);

        // 设置流程历史任务信息
        List<HisTask> insHisList = FlowFactory.hisTaskService().setSkipInsHis(task, nextNodes, flowParams);

        // 设置结束节点相关信息
        setEndInfo(instance, addTasks, flowParams, insHisList, task);

        // 设置流程实例信息
        setSkipInstance(instance, addTasks, flowParams);

        // 一票否决（谨慎使用），如果退回，退回指向节点后还存在其他正在执行的代办任务，转历史任务，状态都为失效,重走流程。
        oneVoteVeto(task, flowParams.getSkipType(), nextNode.getNodeCode());

        // 更新流程信息
        updateFlowInfo(task, instance, insHisList, addTasks);

        // 处理未完成的任务，当流程完成，还存在代办任务未完成，转历史任务，状态完成。
        handUndoneTask(instance, null);

        // 最后判断是否存在监听器，存在执行监听器
        ListenerUtil.executeListener(new ListenerVariable(instance, flowParams.getVariable(), task, addTasks)
                , NowNode, nextNodes);
        return instance;
    }

    @Override
    public Instance termination(Long taskId, FlowParams flowParams) {
        // 获取待办任务
        Task task = getById(taskId);
        AssertUtil.isTrue(ObjectUtil.isNull(task), ExceptionCons.NOT_FOUNT_TASK);
        // 获取当前流程
        Instance instance = FlowFactory.insService().getById(task.getInstanceId());
        AssertUtil.isTrue(ObjectUtil.isNull(instance), ExceptionCons.NOT_FOUNT_INSTANCE);
        return termination(instance, task, flowParams);
    }

    @Override
    public Instance termination(Instance instance, Task task, FlowParams flowParams) {
        // 所有代办转历史
        List<Node> nodeList = FlowFactory.nodeService().list(FlowFactory.newNode()
                .setDefinitionId(instance.getDefinitionId()).setNodeType(NodeType.END.getKey()));
        Node endNode = nodeList.get(0);
        List<HisTask> insHisList = FlowFactory.hisTaskService().setSkipInsHis(task, nodeList, flowParams);
        insHisList.add(FlowFactory.newHisTask()
                .setInstanceId(task.getInstanceId())
                .setNodeCode(endNode.getNodeCode())
                .setNodeName(endNode.getNodeName())
                .setNodeType(endNode.getNodeType())
                .setPermissionFlag(task.getPermissionFlag())
                .setTenantId(task.getTenantId())
                .setDefinitionId(task.getDefinitionId())
                .setFlowStatus(FlowStatus.FINISHED.getKey())
                .setCreateTime(new Date())
                .setApprover(flowParams.getCreateBy()));
        FlowFactory.hisTaskService().saveBatch(insHisList);

        // 流程实例完成
        instance.setNodeType(endNode.getNodeType());
        instance.setNodeCode(endNode.getNodeCode());
        instance.setNodeName(endNode.getNodeName());
        instance.setFlowStatus(FlowStatus.FINISHED.getKey());
        FlowFactory.insService().updateById(instance);

        // 处理未完成的任务，当流程完成，还存在代办任务未完成，转历史任务，状态完成。
        handUndoneTask(instance, task.getId());

        return instance;
    }

    @Override
    public boolean deleteByInsIds(List<Long> instanceIds) {
        return SqlHelper.retBool(getDao().deleteByInsIds(instanceIds));
    }

    @Override
    public Node getNextNode(Node NowNode, Task task, FlowParams flowParams) {
        AssertUtil.isNull(task.getDefinitionId(), ExceptionCons.NOT_DEFINITION_ID);
        AssertUtil.isBlank(task.getNodeCode(), ExceptionCons.LOST_NODE_CODE);
        // 如果指定了跳转节点，则判断权限，直接获取节点
        if (StringUtils.isNotEmpty(flowParams.getNodeCode())) {
            return checkSkipAppointAuth(task, flowParams);
        }
        List<Skip> skips = FlowFactory.skipService().list(FlowFactory.newSkip()
                .setDefinitionId(task.getDefinitionId()).setNowNodeCode(task.getNodeCode()));
        Skip nextSkip = checkAuthAndCondition(NowNode, task, skips, flowParams);
        AssertUtil.isTrue(ObjectUtil.isNull(nextSkip), ExceptionCons.NULL_DEST_NODE);
        List<Node> nodes = FlowFactory.nodeService()
                .getByNodeCodes(Collections.singletonList(nextSkip.getNextNodeCode()), task.getDefinitionId());
        AssertUtil.isTrue(CollUtil.isEmpty(nodes), ExceptionCons.NOT_NODE_DATA);
        AssertUtil.isTrue(nodes.size() > 1, "[" + nextSkip.getNextNodeCode() + "]" + ExceptionCons.SAME_NODE_CODE);
        AssertUtil.isTrue(NodeType.isStart(nodes.get(0).getNodeType()), ExceptionCons.FRIST_FORBID_BACK);
        return nodes.get(0);

    }

    @Override
    public List<Node> getNextByCheckGateWay(FlowParams flowParams, Node nextNode) {
        List<Node> nextNodes = new ArrayList<>();
        if (NodeType.isGateWay(nextNode.getNodeType())) {
            List<Skip> skipsGateway = FlowFactory.skipService().list(FlowFactory.newSkip()
                    .setDefinitionId(nextNode.getDefinitionId()).setNowNodeCode(nextNode.getNodeCode()));
            if (CollUtil.isEmpty(skipsGateway)) {
                return null;
            }

            if (!NodeType.isStart(nextNode.getNodeType())) {
                skipsGateway = skipsGateway.stream().filter(t -> {
                    if (NodeType.isGateWaySerial(nextNode.getNodeType())) {
                        AssertUtil.isTrue(MapUtil.isEmpty(flowParams.getVariable()), ExceptionCons.MUST_CONDITIONVALUE_NODE);
                        if (ObjectUtil.isNotNull(t.getSkipCondition())) {
                            return ExpressionUtil.eval(t.getSkipCondition(), flowParams.getVariable());
                        }
                        return true;
                    }
                    // 并行网关可以返回多个跳转
                    return true;

                }).collect(Collectors.toList());
            }
            AssertUtil.isTrue(CollUtil.isEmpty(skipsGateway), ExceptionCons.NULL_CONDITIONVALUE_NODE);

            List<String> nextNodeCodes = StreamUtils.toList(skipsGateway, Skip::getNextNodeCode);
            nextNodes = FlowFactory.nodeService()
                    .getByNodeCodes(nextNodeCodes, nextNode.getDefinitionId());
            AssertUtil.isTrue(CollUtil.isEmpty(nextNodes), ExceptionCons.NOT_NODE_DATA);
        } else {
            nextNodes.add(nextNode);
        }
        return nextNodes;
    }

    @Override
    public Task addTask(Node node, Instance instance, FlowParams flowParams) {
        Task addTask = FlowFactory.newTask();
        Date date = new Date();
        FlowFactory.dataFillHandler().idFill(addTask);
        addTask.setDefinitionId(instance.getDefinitionId());
        addTask.setInstanceId(instance.getId());
        addTask.setNodeCode(node.getNodeCode());
        addTask.setNodeName(node.getNodeName());
        addTask.setNodeType(node.getNodeType());
        String permissionFlag;
        if (StringUtils.isNotEmpty(node.getDynamicPermissionFlag())) {
            permissionFlag = node.getDynamicPermissionFlag();
        } else {
            permissionFlag = node.getPermissionFlag();
            // 如果设置了发起人审批，则需要动态替换权限标识
            if (StringUtils.isNotEmpty(permissionFlag) && permissionFlag.contains(FlowCons.WARMFLOWINITIATOR)) {
                permissionFlag = permissionFlag.replace(FlowCons.WARMFLOWINITIATOR, instance.getCreateBy());
            }
        }
        addTask.setPermissionFlag(permissionFlag);
        addTask.setApprover(flowParams.getCreateBy());
        addTask.setFlowStatus(setFlowStatus(node.getNodeType(), flowParams.getSkipType()));
        addTask.setCreateTime(date);
        addTask.setTenantId(flowParams.getTenantId());
        return addTask;
    }

    @Override
    public Integer setFlowStatus(Integer nodeType, String skipType) {
        // 根据审批动作确定流程状态
        if (NodeType.isStart(nodeType)) {
            return FlowStatus.TOBESUBMIT.getKey();
        } else if (NodeType.isEnd(nodeType)) {
            return FlowStatus.FINISHED.getKey();
        } else if (SkipType.isReject(skipType)) {
            return FlowStatus.REJECT.getKey();
        } else {
            return FlowStatus.APPROVAL.getKey();
        }
    }

    @Override
    public boolean transfer(Long taskId, String permissionFlag) {
        return updateById(getById(taskId).setPermissionFlag(permissionFlag));
    }

    @Override
    public Task getNextTask(List<Task> tasks) {
        if (tasks.size() == 1) {
            return tasks.get(0);
        }
        for (Task task : tasks) {
            if (NodeType.isEnd(task.getNodeType())) {
                return task;
            }
        }
        return tasks.stream().max(Comparator.comparingLong(Task::getId)).orElse(null);
    }

    /**
     * 构建增代办任务
     *
     * @param flowParams
     * @param task
     * @param instance
     * @param nextNode
     * @return
     */
    private List<Task> buildAddTasks(FlowParams flowParams, Task task, Instance instance
            , List<Node> nextNodes, Node nextNode) {
        boolean buildFlag = false;
        // 下个节点非并行网关节点，可以直接生成下一个代办任务
        if (!NodeType.isGateWayParallel(nextNode.getNodeType())) {
            buildFlag = true;
        } else {
            // 下个节点是并行网关节点，判断前置节点是否都完成
            if (gateWayParallelIsFinish(task, instance, nextNode.getNodeCode())) {
                buildFlag = true;
            }
        }

        if (buildFlag) {
            List<Task> addTasks = new ArrayList<>();
            for (Node node : nextNodes) {
                Task flowTask = addTask(node, instance, flowParams);
                flowTask.setTenantId(task.getTenantId());
                addTasks.add(flowTask);
            }
            return addTasks;
        }
        return null;
    }

    /**
     * 判断并行网关节点前置任务是否都完成
     * 多条线路汇聚到并行网关，必须所有任务都完成，才能继续。 根据并行网关节点，查询前面的节点是否都完成，
     * 判断规则，获取网关所有前置节点，并且查询是否有历史任务记录，前前置节点完成时间是否早于前置节点
     *
     * @param task
     * @param instance
     * @param nextNodeCode
     * @return
     */
    private boolean gateWayParallelIsFinish(Task task, Instance instance, String nextNodeCode) {
        List<Skip> allSkips = FlowFactory.skipService().list(FlowFactory.newSkip()
                .setDefinitionId(instance.getDefinitionId()));
        Map<String, List<Skip>> skipNextMap = StreamUtils.groupByKeyFilter(skip -> !task.getNodeCode()
                        .equals(skip.getNowNodeCode()) || !nextNodeCode.equals(skip.getNextNodeCode())
                , allSkips, Skip::getNextNodeCode);
        List<Skip> oneLastSkips = skipNextMap.get(nextNodeCode);
        // 说明没有其他前置节点，那可以完成往下执行
        if (CollUtil.isEmpty(oneLastSkips)) {
            return true;
        }
        if (CollUtil.isNotEmpty(oneLastSkips)) {
            for (Skip oneLastSkip : oneLastSkips) {
                HisTask oneLastHisTask = CollUtil.getOne(FlowFactory.hisTaskService()
                        .getNoReject(oneLastSkip.getNowNodeCode(), instance.getId()));
                // 查询前置节点是否有完成记录
                if (ObjectUtil.isNull(oneLastHisTask)) {
                    return false;
                }
                List<Skip> twoLastSkips = skipNextMap.get(oneLastSkip.getNowNodeCode());
                for (Skip twoLastSkip : twoLastSkips) {
                    if (NodeType.isStart(twoLastSkip.getNowNodeType())) {
                        return true;
                    } else if (NodeType.isGateWay(twoLastSkip.getNowNodeType())) {
                        // 如果前前置节点是网关，那网关前任意一个任务完成就算完成
                        List<Skip> threeLastSkips = skipNextMap.get(twoLastSkip.getNowNodeCode());
                        for (Skip threeLastSkip : threeLastSkips) {
                            HisTask threeLastHisTask = CollUtil.getOne(FlowFactory.hisTaskService()
                                    .getNoReject(threeLastSkip.getNowNodeCode(), instance.getId()));
                            if (ObjectUtil.isNotNull(threeLastHisTask) && threeLastHisTask.getCreateTime()
                                    .before(oneLastHisTask.getCreateTime())) {
                                return true;
                            }
                        }
                    } else {
                        HisTask twoLastHisTask = CollUtil.getOne(FlowFactory.hisTaskService()
                                .getNoReject(twoLastSkip.getNowNodeCode(), instance.getId()));
                        // 前前置节点完成时间是否早于前置节点，如果是串行网关，那前前置节点必须只有一个完成，如果是并行网关都要完成
                        if (ObjectUtil.isNotNull(twoLastHisTask) && twoLastHisTask.getCreateTime()
                                .before(oneLastHisTask.getCreateTime())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 校验跳转指定节点是否有权限任意跳转
     *
     * @param task
     * @param flowParams
     * @return
     */
    private Node checkSkipAppointAuth(Task task, FlowParams flowParams) {
        List<String> permissionFlags = flowParams.getPermissionFlag();
        List<Node> curNodes = FlowFactory.nodeService()
                .getByNodeCodes(Collections.singletonList(task.getNodeCode()), task.getDefinitionId());
        Node curNode = CollUtil.getOne(curNodes);
        // 判断当前节点是否可以任意跳转
        AssertUtil.isTrue(ObjectUtil.isNull(curNode), ExceptionCons.NOT_NODE_DATA);
        AssertUtil.isFalse(FlowCons.SKIP_ANY_Y.equals(curNode.getSkipAnyNode()), ExceptionCons.SKIP_ANY_NODE);

        AssertUtil.isTrue(StringUtils.isNotEmpty(curNode.getPermissionFlag()) && (CollUtil.isEmpty(permissionFlags)
                || !CollUtil.containsAny(permissionFlags, ArrayUtil.strToArrAy(curNode.getPermissionFlag(),
                ","))), ExceptionCons.NULL_ROLE_NODE);
        List<Node> nextNodes = FlowFactory.nodeService()
                .getByNodeCodes(Collections.singletonList(flowParams.getNodeCode()), task.getDefinitionId());
        return CollUtil.getOne(nextNodes);
    }

    /**
     * 权限和条件校验
     *
     * @param task
     * @param skips
     * @param flowParams
     * @return
     */
    private Skip checkAuthAndCondition(Node NowNode, Task task, List<Skip> skips, FlowParams flowParams) {
        if (CollUtil.isEmpty(skips)) {
            return null;
        }
        List<String> permissionFlags = flowParams.getPermissionFlag();
        AssertUtil.isTrue(ObjectUtil.isNull(NowNode), ExceptionCons.NOT_NODE_DATA);
        // 如果有动态权限标识，则优先使用动态权限标识
        String permissionFlag = "";
        if (StringUtils.isNotEmpty(NowNode.getDynamicPermissionFlag())) {
            permissionFlag = NowNode.getDynamicPermissionFlag();
        } else if (StringUtils.isNotEmpty(task.getPermissionFlag())) {
            permissionFlag = task.getPermissionFlag();
        }
        AssertUtil.isTrue(StringUtils.isNotEmpty(permissionFlag) && (CollUtil.isEmpty(permissionFlags)
                || !CollUtil.containsAny(permissionFlags, ArrayUtil.strToArrAy(permissionFlag,
                ","))), ExceptionCons.NULL_ROLE_NODE);


        if (!NodeType.isStart(task.getNodeType())) {
            skips = skips.stream().filter(t -> {
                if (StringUtils.isNotEmpty(t.getSkipType())) {
                    return (flowParams.getSkipType()).equals(t.getSkipType());
                }
                return true;
            }).collect(Collectors.toList());
        }
        AssertUtil.isTrue(CollUtil.isEmpty(skips), ExceptionCons.NULL_CONDITIONVALUE_NODE);
        return skips.get(0);
    }

    // 设置结束节点相关信息
    private void setEndInfo(Instance instance, List<Task> addTasks, FlowParams flowParams
            , List<HisTask> insHisList, Task task) {
        if (CollUtil.isNotEmpty(addTasks)) {
            addTasks.removeIf(addTask -> {
                if (NodeType.isEnd(addTask.getNodeType())) {
                    HisTask insHis = FlowFactory.newHisTask()
                            .setInstanceId(addTask.getInstanceId())
                            .setNodeCode(addTask.getNodeCode())
                            .setNodeName(addTask.getNodeName())
                            .setNodeType(addTask.getNodeType())
                            .setPermissionFlag(task.getPermissionFlag())
                            .setTenantId(addTask.getTenantId())
                            .setDefinitionId(addTask.getDefinitionId())
                            .setFlowStatus(FlowStatus.FINISHED.getKey())
                            .setCreateTime(new Date())
                            .setApprover(flowParams.getCreateBy());
                    insHisList.add(insHis);
                    instance.setNodeType(addTask.getNodeType());
                    instance.setNodeCode(addTask.getNodeCode());
                    instance.setNodeName(addTask.getNodeName());
                    instance.setFlowStatus(FlowStatus.FINISHED.getKey());
                    return true;
                }
                return false;
            });
        }
    }

    private void setSkipInstance(Instance instance, List<Task> addTasks, FlowParams flowParams) {
        instance.setUpdateTime(new Date());
        Map<String, Object> variable = flowParams.getVariable();
        if (MapUtil.isNotEmpty(variable)) {
            String variableStr = instance.getVariable();
            if (StringUtils.isNotEmpty(variableStr)) {
                Map<String, Object> deserialize = ONode.deserialize(variableStr);
                deserialize.putAll(variable);
            }
            instance.setVariable(ONode.serialize(variable));
        }
        // 流程未完成，存在后续任务，才重新设置流程信息
        if (!FlowStatus.isFinished(instance.getFlowStatus()) && CollUtil.isNotEmpty(addTasks)) {
            Task nextTask = getNextTask(addTasks);
            instance.setNodeType(nextTask.getNodeType());
            instance.setNodeCode(nextTask.getNodeCode());
            instance.setNodeName(nextTask.getNodeName());
            instance.setFlowStatus(setFlowStatus(nextTask.getNodeType()
                    , flowParams.getSkipType()));
        }
    }

    /**
     * 一票否决（谨慎使用），如果退回，退回指向节点后还存在其他正在执行的代办任务，转历史任务，状态都为退回,重走流程。
     *
     * @param task
     * @param skipType
     * @param nextNodeCode
     * @return
     */
    private void oneVoteVeto(Task task, String skipType, String nextNodeCode) {
        // 一票否决（谨慎使用），如果退回，退回指向节点后还存在其他正在执行的代办任务，转历史任务，状态失效,重走流程。
        if (SkipType.isReject(skipType)) {
            List<Task> tasks = list(FlowFactory.newTask().setInstanceId(task.getInstanceId()));
            List<Skip> allSkips = FlowFactory.skipService().list(FlowFactory.newSkip()
                    .setDefinitionId(task.getDefinitionId()));
            // 排除执行当前节点的流程跳转
            Map<String, List<Skip>> skipMap = StreamUtils.groupByKeyFilter(skip ->
                    !task.getNodeCode().equals(skip.getNextNodeCode()), allSkips, Skip::getNextNodeCode);
            // 属于退回指向节点的后置未完成的任务
            List<Task> noDoneTasks = new ArrayList<>();
            for (Task flowTask : tasks) {
                if (!task.getNodeCode().equals(flowTask.getNodeCode())) {
                    List<Skip> lastSkips = skipMap.get(flowTask.getNodeCode());
                    if (judgeReject(nextNodeCode, lastSkips, skipMap)) {
                        noDoneTasks.add(flowTask);
                    }
                }
            }
            if (CollUtil.isNotEmpty(noDoneTasks)) {
                convertHisTask(noDoneTasks, FlowStatus.INVALID.getKey(), null);
            }
        }
    }


    /**
     * 判断是否属于退回指向节点的后置未完成的任务
     *
     * @param nextNodeCode
     * @param lastSkips
     * @param skipMap
     * @return
     */
    private boolean judgeReject(String nextNodeCode, List<Skip> lastSkips
            , Map<String, List<Skip>> skipMap) {
        if (CollUtil.isNotEmpty(lastSkips)) {
            for (Skip lastSkip : lastSkips) {
                if (nextNodeCode.equals(lastSkip.getNowNodeCode())) {
                    return true;
                }
                List<Skip> lastLastSkips = skipMap.get(lastSkip.getNowNodeCode());
                return judgeReject(nextNodeCode, lastLastSkips, skipMap);
            }
        }
        return false;
    }

    /**
     * 处理未完成的任务，当流程完成，还存在代办任务未完成，转历史任务，状态完成。
     *
     * @param instance
     * @param taskId   排除此任务
     */
    private void handUndoneTask(Instance instance, Long taskId) {
        if (FlowStatus.isFinished(instance.getFlowStatus())) {
            List<Task> taskList = list(FlowFactory.newTask().setInstanceId(instance.getId()));
            if (CollUtil.isNotEmpty(taskList)) {
                convertHisTask(taskList, FlowStatus.FINISHED.getKey(), taskId);
            }
        }
    }

    /**
     * 代办任务转历史任务。
     *
     * @param taskList
     */
    private void convertHisTask(List<Task> taskList, Integer flowStatus, Long taskId) {
        List<HisTask> insHisList = new ArrayList<>();
        for (Task task : taskList) {
            if (ObjectUtil.isNotNull(taskId) && !task.getId().equals(taskId)) {
                HisTask insHis = FlowFactory.newHisTask();
                insHis.setId(task.getId());
                insHis.setInstanceId(task.getInstanceId());
                insHis.setDefinitionId(task.getDefinitionId());
                insHis.setNodeCode(task.getNodeCode());
                insHis.setNodeName(task.getNodeName());
                insHis.setNodeType(task.getNodeType());
                insHis.setPermissionFlag(task.getPermissionFlag());
                insHis.setFlowStatus(flowStatus);
                insHis.setCreateTime(new Date());
                insHis.setTenantId(task.getTenantId());
                insHisList.add(insHis);
            }
        }
        removeByIds(StreamUtils.toList(taskList, Task::getId));
        FlowFactory.hisTaskService().saveBatch(insHisList);
    }

    /**
     * 更新流程信息
     *
     * @param task
     * @param instance
     * @param insHisList
     * @param addTasks
     */
    private void updateFlowInfo(Task task, Instance instance, List<HisTask> insHisList
            , List<Task> addTasks) {
        removeById(task.getId());
        FlowFactory.hisTaskService().saveBatch(insHisList);
        if (CollUtil.isNotEmpty(addTasks)) {
            saveBatch(addTasks);
        }
        FlowFactory.insService().updateById(instance);
    }
}
