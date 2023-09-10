package com.warm.flow.core.service.impl;

import com.warm.flow.core.FlowFactory;
import com.warm.flow.core.constant.FlowConstant;
import com.warm.flow.core.domain.dto.FlowParams;
import com.warm.flow.core.domain.entity.*;
import com.warm.flow.core.enums.ApprovalAction;
import com.warm.flow.core.enums.FlowStatus;
import com.warm.flow.core.enums.NodeType;
import com.warm.flow.core.exception.FlowException;
import com.warm.flow.core.mapper.FlowInstanceMapper;
import com.warm.flow.core.service.InsService;
import com.warm.flow.core.utils.AssertUtil;
import com.warm.mybatis.core.service.impl.WarmServiceImpl;
import com.warm.tools.utils.ArrayUtil;
import com.warm.tools.utils.CollUtil;
import com.warm.tools.utils.IdUtils;
import com.warm.tools.utils.StringUtils;

import java.util.*;
import java.util.stream.Collectors;


/**
 * 流程实例Service业务层处理
 *
 * @author hh
 * @date 2023-03-29
 */
public class InsServiceImpl extends WarmServiceImpl<FlowInstanceMapper, FlowInstance> implements InsService {

    @Override
    public List<FlowInstance> getByIdWithLock(List<Long> ids) {
        AssertUtil.isFalse(CollUtil.isEmpty(ids), FlowConstant.NOT_FOUNT_INSTANCE_ID);
        for (Long id : ids) {
            AssertUtil.isNull(id, "流程定义id不能为空!");
        }
        return getMapper().getByIdWithLock(ids);
    }

    @Override
    public FlowInstance startFlow(String businessId, FlowParams flowUser) {

        return toStartFlow(Collections.singletonList(businessId), flowUser).get(0);
    }

    @Override
    public List<FlowInstance> startFlow(List<String> businessIds, FlowParams flowUser) {
        return toStartFlow(businessIds, flowUser);
    }


    @Override
    public FlowInstance skipFlow(Long instanceId, FlowParams flowUser) {
        return toSkipFlow(Collections.singletonList(instanceId), flowUser).get(0);
    }

    @Override
    public List<FlowInstance> skipFlow(List<Long> instanceIds, FlowParams flowUser) {
        return toSkipFlow(instanceIds, flowUser);
    }

    @Override
    public boolean removeTask(Long instanceId) {
        return removeTask(Collections.singletonList(instanceId));
    }


    @Override
    public boolean removeTask(List<Long> instanceIds) {
        return toRemoveTask(instanceIds);
    }

    /**
     * 根据开始的结点,业务id集合开启流程
     *
     * @param businessIds
     * @param flowUser
     * @return
     */
    private List<FlowInstance> toStartFlow(List<String> businessIds, FlowParams flowUser) {
        AssertUtil.isNull(flowUser.getFlowCode(), FlowConstant.NULL_FLOW_CODE);
        AssertUtil.isFalse(CollUtil.isEmpty(businessIds), FlowConstant.NULL_BUSINESS_ID);
        // 根据流程编码获取开启的唯一流程的流程结点集合
        List<FlowNode> nodes = FlowFactory.nodeService().getLastByFlowCode(flowUser.getFlowCode());
        AssertUtil.isFalse(CollUtil.isEmpty(nodes), FlowConstant.NOT_PUBLISH_NODE);
        // 获取开始结点
        FlowNode startNode = getFirstNode(nodes);

        List<FlowInstance> instances = new ArrayList<>();
        List<FlowTask> taskList = new ArrayList<>();
        for (String businessId : businessIds) {
            AssertUtil.isBlank(businessId, FlowConstant.NULL_BUSINESS_ID);
            // 设置流程实例对象
            FlowInstance instance = setStartInstance(startNode, businessId, flowUser);
            // 设置流程历史任务记录对象
            FlowTask task = setStartTask(startNode, instance, flowUser);
            instances.add(instance);
            taskList.add(task);
        }
        saveBatch(instances);
        FlowFactory.taskService().saveBatch(taskList);
        return instances;
    }

    private List<FlowInstance> toSkipFlow(List<Long> instanceIds, FlowParams flowUser) {
        AssertUtil.isFalse(StringUtils.isNotEmpty(flowUser.getMessage())
                && flowUser.getMessage().length() > 500, FlowConstant.MSG_OVER_LENGTH);
        AssertUtil.isTrue(StringUtils.isNotEmpty(flowUser.getSkipType()), FlowConstant.NULL_CONDITIONVALUE);
        // 获取当前流程
        List<FlowInstance> instances = FlowFactory.insService().getByIdWithLock(instanceIds);
        AssertUtil.isFalse(CollUtil.isEmpty(instances), FlowConstant.NOT_FOUNT_INSTANCE);
        AssertUtil.isFalse(instances.size() < instanceIds.size(), FlowConstant.LOST_FOUNT_INSTANCE);
        // TODO min 后续考虑并发问题，待办任务和实例表不同步
        // 获取待办任务
        List<FlowTask> taskList = FlowFactory.taskService().getByInsIds(instanceIds);
        AssertUtil.isFalse(CollUtil.isEmpty(taskList), FlowConstant.NOT_FOUNT_TASK);
        AssertUtil.isFalse(taskList.size() < instanceIds.size(), FlowConstant.LOST_FOUNT_TASK);
        // 校验这些流程的流程状态是否相同，只有相同的情况下，下面才好做统一处理
        checkSameStatus(taskList);
        Map<Long, FlowTask> taskMap = taskList.stream()
                .collect(Collectors.toMap(FlowTask::getInstanceId, flowTask -> flowTask));

        List<FlowHisTask> insHisList = new ArrayList<>();
        // 获取关联的结点
        FlowNode nextNode = getNextNode(taskList.get(0), flowUser);
        for (FlowInstance instance : instances) {
            // 更新流程实例信息
            setSkipInstance(nextNode, instance, flowUser);
            FlowTask task = taskMap.get(instance.getId());
            // 设置流程历史任务信息
            FlowHisTask insHis = setSkipInsHis(task, nextNode, flowUser);
            // 更新待办任务
            setSkipTask(nextNode, task, flowUser);

            insHisList.add(insHis);
        }
        FlowFactory.hisTaskService().saveBatch(insHisList);
        FlowFactory.taskService().updateBatch(taskList);
        updateBatch(instances);
        return instances;
    }

    /**
     * 设置流程实例信息
     *
     * @param nextNode
     * @param instance
     */
    private void setSkipInstance(FlowNode nextNode, FlowInstance instance, FlowParams flowUser) {
        instance.setNodeType(nextNode.getNodeType());
        instance.setFlowStatus(setFlowStatus(nextNode.getNodeType(), flowUser.getSkipType(), false));
        instance.setUpdateTime(new Date());
    }

    /**
     * 设置待办任务信息
     *
     * @param nextNode
     * @param task
     */
    private void setSkipTask(FlowNode nextNode, FlowTask task, FlowParams flowUser) {
        task.setNodeCode(nextNode.getNodeCode());
        task.setNodeName(nextNode.getNodeName());
        task.setNodeType(nextNode.getNodeType());
        task.setApprover(flowUser.getCreateBy());
        task.setPermissionFlag(nextNode.getPermissionFlag());
        task.setFlowStatus(setFlowStatus(nextNode.getNodeType(), flowUser.getSkipType(), false));
        task.setUpdateTime(new Date());
        task.setTenantId(flowUser.getTenantId());
    }

    /**
     * 设置流程历史任务信息
     *
     * @param task
     * @param nextNode
     * @return
     */
    private FlowHisTask setSkipInsHis(FlowTask task, FlowNode nextNode, FlowParams flowUser) {
        FlowHisTask insHis = new FlowHisTask();
        insHis.setId(IdUtils.nextId());
        insHis.setInstanceId(task.getInstanceId());
        insHis.setDefinitionId(task.getDefinitionId());
        insHis.setNodeCode(task.getNodeCode());
        insHis.setNodeName(task.getNodeName());
        insHis.setNodeType(task.getNodeType());
        insHis.setPermissionFlag(task.getPermissionFlag());
        insHis.setTargetNodeCode(nextNode.getNodeCode());
        insHis.setTargetNodeName(nextNode.getNodeName());
        insHis.setFlowStatus(setFlowStatus(nextNode.getNodeType(), flowUser.getSkipType(), true));
        insHis.setMessage(flowUser.getMessage());
        insHis.setCreateTime(new Date());
        insHis.setApprover(flowUser.getCreateBy());
        insHis.setTenantId(task.getTenantId());
        return insHis;
    }

    /**
     * @param nodeType       节点类型（开始节点、中间节点、结束节点）
     * @param skipType 流程条件
     * @param type           实体类型（历史任务实体为true）
     */
    private Integer setFlowStatus(Integer nodeType, String skipType, boolean type) {
        // 根据审批动作确定流程状态
        if (NodeType.END.getKey().equals(nodeType)) {
            return FlowStatus.FINISHED.getKey();
        } else if (ApprovalAction.REJECT.getKey().equals(skipType)) {
            return FlowStatus.REJECT.getKey();
        } else if (type) {
            return FlowStatus.PASS.getKey();
        } else {
            return FlowStatus.APPROVAL.getKey();
        }
    }

    /**
     * 设置流程实例对象
     *
     * @param startNode
     * @param businessId
     * @return
     */
    private FlowInstance setStartInstance(FlowNode startNode, String businessId
            , FlowParams flowUser) {
        FlowInstance instance = new FlowInstance();
        Date now = new Date();
        Long id = IdUtils.nextId();
        instance.setId(id);
        instance.setDefinitionId(startNode.getDefinitionId());
        instance.setBusinessId(businessId);
        // 关联业务id,起始后面可以不用到业务id,传业务id目前来看只是为了批量创建流程的时候能创建出有区别化的流程,也是为了后期需要用到businessId。
        instance.setNodeType(startNode.getNodeType());
        instance.setFlowStatus(FlowStatus.TOBESUBMIT.getKey());
        instance.setCreateTime(now);
        instance.setUpdateTime(now);
        instance.setCreateBy(flowUser.getCreateBy());
        instance.setExt(flowUser.getExt());
        return instance;
    }

    /**
     * 设置流程待办任务对象
     *
     * @param startNode
     * @param instance
     * @return
     */
    private FlowTask setStartTask(FlowNode startNode, FlowInstance instance, FlowParams flowUser) {
        FlowTask task = new FlowTask();
        Date date = new Date();
        task.setId(IdUtils.nextId());
        task.setDefinitionId(instance.getDefinitionId());
        task.setInstanceId(instance.getId());
        task.setNodeCode(startNode.getNodeCode());
        task.setNodeName(startNode.getNodeName());
        task.setNodeType(startNode.getNodeType());
        task.setPermissionFlag(startNode.getPermissionFlag());
        task.setApprover(flowUser.getCreateBy());
        task.setFlowStatus(FlowStatus.TOBESUBMIT.getKey());
        task.setCreateTime(date);
        task.setUpdateTime(date);
        task.setTenantId(flowUser.getTenantId());
        return task;
    }

    /**
     * 批量流程校验,如批量处理一批流程,其中有些流程在a状态，有些在b状态，这样就会抛MUL_FROM_STATUS异常。
     * 这种情况调整为根据流程状态进行分批批量跳转就可以了
     *
     * @param taskList
     */
    private void checkSameStatus(List<FlowTask> taskList) {
        Map<String, List<FlowTask>> groupMap = taskList.stream().collect(Collectors.groupingBy(t -> t.getDefinitionId() + "_" + t.getNodeCode()));
        if (groupMap.size() > 1) {
            throw new FlowException(FlowConstant.MUL_FROM_STATUS);
        }
    }

    /**
     * 权限和条件校验
     *
     * @param skips
     * @return
     */
    private FlowSkip checkAuthAndCondition(FlowTask task, List<FlowSkip> skips, FlowParams flowUser) {
        if (CollUtil.isEmpty(skips)) {
            return null;
        }
        List<String> permissionFlags = flowUser.getPermissionFlag();
        FlowNode flowNode = new FlowNode();
        flowNode.setDefinitionId(task.getDefinitionId());
        flowNode.setNodeCode(task.getNodeCode());
        FlowNode node = FlowFactory.nodeService().getOne(flowNode);

        AssertUtil.isFalse(StringUtils.isNotEmpty(node.getPermissionFlag()) && (CollUtil.isEmpty(permissionFlags)
                || !CollUtil.containsAny(permissionFlags, ArrayUtil.strToArrAy(node.getPermissionFlag(),
                ","))), FlowConstant.NULL_ROLE_NODE);

        if (!NodeType.START.getKey().equals(task.getNodeType())) {
            skips = skips.stream().filter(t -> (flowUser.getSkipType() + ":" + flowUser.getSkipCondition())
                    .equals(t.getSkipType() + ":" + t.getSkipCondition())).collect(Collectors.toList());
        }
        AssertUtil.isFalse(skips.isEmpty(), FlowConstant.NULL_CONDITIONVALUE_NODE);
        // 第一个结点
        return skips.get(0);
    }

    /**
     * 根据流程id+当前流程结点编码获取与之直接关联(其为源结点)的结点。 definitionId:流程id nodeCode:当前流程状态
     * skipType:跳转条件,没有填写的话不做校验
     *
     * @param task
     * @param flowUser
     * @return
     */
    private FlowNode getNextNode(FlowTask task, FlowParams flowUser) {
        AssertUtil.isNull(task.getDefinitionId(), FlowConstant.NOT_DEFINITION_ID);
        AssertUtil.isBlank(task.getNodeCode(), FlowConstant.LOST_NODE_CODE);
        FlowSkip skipCondition = new FlowSkip();
        skipCondition.setDefinitionId(task.getDefinitionId());
        skipCondition.setNowNodeCode(task.getNodeCode());
        List<FlowSkip> flowSkips = FlowFactory.skipService().list(skipCondition);
        FlowSkip nextSkip = checkAuthAndCondition(task, flowSkips, flowUser);
        AssertUtil.isFalse(nextSkip == null, FlowConstant.NULL_DEST_NODE);
        FlowNode query = new FlowNode();
        query.setDefinitionId(task.getDefinitionId());
        query.setNodeCode(nextSkip.getNextNodeCode());
        List<FlowNode> nodes = FlowFactory.nodeService().list(query);
        AssertUtil.isFalse(nodes.isEmpty(), FlowConstant.NOT_NODE_DATA);
        AssertUtil.isFalse(nodes.size() > 1, "[" + nextSkip.getNextNodeCode() + "]" + FlowConstant.SAME_NODE_CODE);
        return nodes.get(0);

    }

    /**
     * 有且只能有一个开始结点
     *
     * @param nodes
     * @return
     */
    private FlowNode getFirstNode(List<FlowNode> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            if (NodeType.START.getKey().equals(nodes.get(i).getNodeType())) {
                return nodes.get(i);
            }
        }
        throw new FlowException(FlowConstant.LOST_START_NODE);
    }

    private boolean toRemoveTask(List<Long> instanceIds) {
        AssertUtil.isFalse(CollUtil.isEmpty(instanceIds), FlowConstant.NULL_INSTANCE_ID);
        boolean success = FlowFactory.taskService().deleteByInsIds(instanceIds);
        if (success) {
            FlowFactory.hisTaskService().deleteByInsIds(instanceIds);
            return FlowFactory.insService().removeByIds(instanceIds);
        }
        return false;
    }

}