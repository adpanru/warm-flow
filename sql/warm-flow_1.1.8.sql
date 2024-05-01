ALTER TABLE `flow_his_task`
    MODIFY COLUMN `flow_status` tinyint(1) NOT NULL COMMENT '流程状态（0待提交 1审批中 2 审批通过 8已完成 9已退回 10失效）' AFTER `permission_flag`;

ALTER TABLE `flow_instance`
    MODIFY COLUMN `flow_status` tinyint(1) NOT NULL COMMENT '流程状态（0待提交 1审批中 2 审批通过 8已完成 9已退回 10失效）' AFTER `variable`;

ALTER TABLE `flow_skip`
    MODIFY COLUMN `skip_type` varchar(40) DEFAULT NULL COMMENT '跳转类型（PASS审批通过 REJECT退回）' AFTER `skip_name`;

ALTER TABLE `flow_task`
    MODIFY COLUMN `flow_status` tinyint(1) NOT NULL COMMENT '流程状态（0待提交 1审批中 2 审批通过 8已完成 9已退回 10失效）' AFTER `permission_flag`;