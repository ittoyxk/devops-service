package io.choerodon.devops.app.service.impl;

import static io.choerodon.devops.infra.constant.DevopsClusterCommandConstants.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import net.schmizz.sshj.SSHClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.choerodon.core.exception.CommonException;
import io.choerodon.devops.api.vo.*;
import io.choerodon.devops.app.eventhandler.payload.DevopsClusterInstallPayload;
import io.choerodon.devops.app.service.DevopsClusterNodeOperatorService;
import io.choerodon.devops.app.service.DevopsClusterNodeService;
import io.choerodon.devops.app.service.DevopsClusterService;
import io.choerodon.devops.infra.constant.ClusterCheckConstant;
import io.choerodon.devops.infra.constant.MiscConstants;
import io.choerodon.devops.infra.constant.ResourceCheckConstant;
import io.choerodon.devops.infra.dto.DevopsClusterDTO;
import io.choerodon.devops.infra.dto.DevopsClusterNodeDTO;
import io.choerodon.devops.infra.dto.DevopsClusterOperationRecordDTO;
import io.choerodon.devops.infra.enums.*;
import io.choerodon.devops.infra.mapper.DevopsClusterMapper;
import io.choerodon.devops.infra.mapper.DevopsClusterNodeMapper;
import io.choerodon.devops.infra.mapper.DevopsClusterOperationRecordMapper;
import io.choerodon.devops.infra.util.*;

@Service
public class DevopsClusterNodeServiceImpl implements DevopsClusterNodeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevopsClusterNodeServiceImpl.class);
    /**
     * 节点检查进度redis的key
     */
    public static final String NODE_CHECK_STEP_REDIS_KEY_TEMPLATE = "node-check-step-%d-%s";
    private static final String ERROR_DELETE_NODE_FAILED = "error.delete.node.failed";
    private static final String ERROR_ADD_NODE_FAILED = "error.add.node.failed";
    private static final String ERROR_ADD_NODE_ROLE_FAILED = "error.add.node.role.failed";
    private static final String CLUSTER_STATUS_SYNC_REDIS_LOCK = "cluster-status-sync-lock";
    @Value(value = "${devops.helm.download-url}")
    private String helmDownloadUrl;
    @Autowired
    private SshUtil sshUtil;
    @Autowired
    private DevopsClusterMapper devopsClusterMapper;
    @Autowired
    private DevopsClusterNodeMapper devopsClusterNodeMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private DevopsClusterOperationRecordMapper devopsClusterOperationRecordMapper;
    @Autowired
    private DevopsClusterService devopsClusterService;
    @Autowired
    private DevopsClusterNodeOperatorService devopsClusterNodeOperatorService;

    @Override
    @Transactional
    public void baseSave(DevopsClusterNodeDTO devopsClusterNodeDTO) {
        if (devopsClusterNodeMapper.insertSelective(devopsClusterNodeDTO) != 1) {
            throw new CommonException(ERROR_ADD_NODE_FAILED);
        }
    }

    @Override
    public void baseUpdateNodeRole(Long id, Integer role) {
        Assert.notNull(id, ClusterCheckConstant.ERROR_NODE_ID_IS_NULL);
        Assert.notNull(role, ClusterCheckConstant.ERROR_ROLE_ID_IS_NULL);

        DevopsClusterNodeDTO devopsClusterNodeDTO = new DevopsClusterNodeDTO();
        devopsClusterNodeDTO.setId(id);
        devopsClusterNodeDTO.setRole(role);
        if (devopsClusterNodeMapper.updateByPrimaryKey(devopsClusterNodeDTO) != 1) {
            throw new CommonException(ERROR_ADD_NODE_ROLE_FAILED);
        }
    }

    @Override
    public boolean testConnection(Long projectId, ClusterHostConnectionVO hostConnectionVO) {
        return SshUtil.sshConnectForOK(hostConnectionVO.getHostIp(),
                hostConnectionVO.getHostPort(),
                hostConnectionVO.getAuthType(),
                hostConnectionVO.getUsername(),
                hostConnectionVO.getPassword());
    }

    @Override
    public void batchInsert(List<DevopsClusterNodeDTO> devopsClusterNodeDTOList) {
        int size = devopsClusterNodeDTOList.size();
        if (devopsClusterNodeMapper.batchInsert(devopsClusterNodeDTOList) != size) {
            throw new CommonException("error.batch.insert.node");
        }
    }

    @Override
    public void deleteByClusterId(Long clusterId) {
        DevopsClusterNodeDTO devopsClusterNodeDTO = new DevopsClusterNodeDTO();
        devopsClusterNodeDTO.setClusterId(clusterId);
        devopsClusterNodeMapper.delete(devopsClusterNodeDTO);
    }

    @Override
    public NodeDeleteCheckVO checkEnableDelete(Long projectId, Long nodeId) {
        Assert.notNull(projectId, ResourceCheckConstant.ERROR_PROJECT_ID_IS_NULL);
        Assert.notNull(nodeId, ClusterCheckConstant.ERROR_NODE_ID_IS_NULL);

        NodeDeleteCheckVO nodeDeleteCheckVO = new NodeDeleteCheckVO();
        // 查询节点类型
        DevopsClusterNodeDTO devopsClusterNodeDTO = devopsClusterNodeMapper.selectByPrimaryKey(nodeId);
        if (ClusterNodeRoleEnum.listMasterRoleSet().contains(devopsClusterNodeDTO.getRole())) {
            if (devopsClusterNodeMapper.countByRoleSet(devopsClusterNodeDTO.getClusterId(), ClusterNodeRoleEnum.listWorkerRoleSet()) < 2) {
                nodeDeleteCheckVO.setEnableDeleteWorker(false);
            }
        }
        if (ClusterNodeRoleEnum.listEtcdRoleSet().contains(devopsClusterNodeDTO.getRole())) {
            if (devopsClusterNodeMapper.countByRoleSet(devopsClusterNodeDTO.getClusterId(), ClusterNodeRoleEnum.listEtcdRoleSet()) < 2) {
                nodeDeleteCheckVO.setEnableDeleteEtcd(false);
            }
        }
        if (ClusterNodeRoleEnum.listWorkerRoleSet().contains(devopsClusterNodeDTO.getRole())) {
            if (devopsClusterNodeMapper.countByRoleSet(devopsClusterNodeDTO.getClusterId(), ClusterNodeRoleEnum.listMasterRoleSet()) < 2) {
                nodeDeleteCheckVO.setEnableDeleteMaster(false);
            }
        }

        return nodeDeleteCheckVO;
    }

    private void checkNodeNumByRole(DevopsClusterNodeDTO devopsClusterNodeDTO) {
        if (ClusterNodeRoleEnum.listMasterRoleSet().contains(devopsClusterNodeDTO.getRole())) {
            if (devopsClusterNodeMapper.countByRoleSet(devopsClusterNodeDTO.getClusterId(), ClusterNodeRoleEnum.listMasterRoleSet()) < 2) {
                throw new CommonException(ClusterCheckConstant.ERROR_MASTER_NODE_ONLY_ONE);
            }
        }
        if (ClusterNodeRoleEnum.listWorkerRoleSet().contains(devopsClusterNodeDTO.getRole())) {
            if (devopsClusterNodeMapper.countByRoleSet(devopsClusterNodeDTO.getClusterId(), ClusterNodeRoleEnum.listWorkerRoleSet()) < 2) {
                throw new CommonException(ClusterCheckConstant.ERROR_WORKER_NODE_ONLY_ONE);
            }
        }
        if (ClusterNodeRoleEnum.listEtcdRoleSet().contains(devopsClusterNodeDTO.getRole())) {
            if (devopsClusterNodeMapper.countByRoleSet(devopsClusterNodeDTO.getClusterId(), ClusterNodeRoleEnum.listWorkerRoleSet()) < 2) {
                throw new CommonException(ClusterCheckConstant.ERROR_ETCD_NODE_ONLY_ONE);
            }
        }
    }

    @Override
    public void delete(Long projectId, Long nodeId) {
        Assert.notNull(projectId, ResourceCheckConstant.ERROR_PROJECT_ID_IS_NULL);
        Assert.notNull(nodeId, ClusterCheckConstant.ERROR_NODE_ID_IS_NULL);


        DevopsClusterNodeDTO devopsClusterNodeDTO = devopsClusterNodeMapper.selectByPrimaryKey(nodeId);
        CommonExAssertUtil.assertTrue(projectId.equals(devopsClusterNodeDTO.getProjectId()), MiscConstants.ERROR_OPERATING_RESOURCE_IN_OTHER_PROJECT);
        checkNodeNumByRole(devopsClusterNodeDTO);

        // 获取锁,失败则抛出异常，成功则程序继续
        String lockKey = String.format(CLUSTER_LOCK_KEY, devopsClusterNodeDTO.getClusterId());
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "lock", 10, TimeUnit.MINUTES))) {
            throw new CommonException(ClusterCheckConstant.ERROR_CLUSTER_STATUS_IS_OPERATING);
        }
        // 更新redis集群操作状态
        DevopsClusterOperatorVO devopsClusterOperatorVO = new DevopsClusterOperatorVO();
        devopsClusterOperatorVO.setClusterId(devopsClusterNodeDTO.getClusterId());
        devopsClusterOperatorVO.setOperating(ClusterOperatingTypeEnum.DELETE_NODE.value());
        devopsClusterOperatorVO.setNodeId(nodeId);
        devopsClusterOperatorVO.setStatus(ClusterStatusEnum.OPERATING.value());
        String operatingKey = String.format(CLUSTER_OPERATING_KEY, devopsClusterNodeDTO.getClusterId());
        stringRedisTemplate.opsForValue().set(operatingKey, JsonHelper.marshalByJackson(devopsClusterOperatorVO), 10, TimeUnit.MINUTES);

        devopsClusterNodeOperatorService.deleteNode(projectId, devopsClusterNodeDTO, lockKey, operatingKey);


    }

    @Override
    public InventoryVO calculateGeneralInventoryValue(List<DevopsClusterNodeDTO> devopsClusterNodeDTOS) {
        InventoryVO inventoryVO = new InventoryVO();
        for (DevopsClusterNodeDTO node : devopsClusterNodeDTOS) {
            if (node.getType().equalsIgnoreCase(ClusterNodeTypeEnum.INNER.getType())) {// 设置所有节点
                if (HostAuthType.ACCOUNTPASSWORD.value().equals(node.getAuthType())) {
                    inventoryVO.getAll().append(String.format(INVENTORY_INI_TEMPLATE_FOR_ALL, node.getName(), node.getHostIp(), node.getHostPort(), node.getUsername(), node.getPassword()))
                            .append(System.lineSeparator());
                } else {
                    //todo 处理密钥认证方式
                }
                // 设置master节点
                if (ClusterNodeRoleEnum.listMasterRoleSet().contains(node.getRole())) {
                    inventoryVO.getKubeMaster().append(node.getName())
                            .append(System.lineSeparator());
                }
                // 设置etcd节点
                if (ClusterNodeRoleEnum.listEtcdRoleSet().contains(node.getRole())) {
                    inventoryVO.getEtcd().append(node.getName())
                            .append(System.lineSeparator());
                }
                // 设置worker节点
                if (ClusterNodeRoleEnum.listWorkerRoleSet().contains(node.getRole())) {
                    inventoryVO.getKubeWorker().append(node.getName())
                            .append(System.lineSeparator());
                }
            }
        }
        return inventoryVO;
    }

    @Override
    @Transactional
    public void baseDelete(Long id) {
        Assert.notNull(id, ClusterCheckConstant.ERROR_NODE_ID_IS_NULL);
        if (devopsClusterNodeMapper.deleteByPrimaryKey(id) != 1) {
            throw new CommonException(ERROR_DELETE_NODE_FAILED);
        }
    }

    @Override
    public void deleteRole(Long projectId, Long nodeId, Integer role) {
        Assert.notNull(projectId, ResourceCheckConstant.ERROR_PROJECT_ID_IS_NULL);
        Assert.notNull(nodeId, ClusterCheckConstant.ERROR_NODE_ID_IS_NULL);
        Assert.notNull(role, ClusterCheckConstant.ERROR_ROLE_ID_IS_NULL);

        DevopsClusterNodeDTO devopsClusterNodeDTO = devopsClusterNodeMapper.selectByPrimaryKey(nodeId);

        // 删除校验
        checkEnableDeleteRole(devopsClusterNodeDTO, role);

        // 获取锁,失败则抛出异常，成功则程序继续
        String lockKey = String.format(CLUSTER_LOCK_KEY, devopsClusterNodeDTO.getClusterId());
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "lock", 10, TimeUnit.MINUTES))) {
            throw new CommonException(ClusterCheckConstant.ERROR_CLUSTER_STATUS_IS_OPERATING);
        }
        // 更新redis集群操作状态
        DevopsClusterOperatorVO devopsClusterOperatorVO = new DevopsClusterOperatorVO();
        devopsClusterOperatorVO.setClusterId(devopsClusterNodeDTO.getClusterId());
        devopsClusterOperatorVO.setOperating(ClusterOperatingTypeEnum.DELETE_NODE_ROLE.value());
        devopsClusterOperatorVO.setNodeId(nodeId);
        devopsClusterOperatorVO.setStatus(ClusterStatusEnum.OPERATING.value());
        String operatingKey = String.format(CLUSTER_OPERATING_KEY, devopsClusterNodeDTO.getClusterId());
        stringRedisTemplate.opsForValue().set(operatingKey, JsonHelper.marshalByJackson(devopsClusterOperatorVO), 10, TimeUnit.MINUTES);

        devopsClusterNodeOperatorService.deleteNodeRole(projectId, devopsClusterNodeDTO, role, lockKey, operatingKey);


    }

    private void checkEnableDeleteRole(DevopsClusterNodeDTO devopsClusterNodeDTO, Integer roleId) {
        if (ClusterNodeRoleEnum.isWorker(roleId)) {
            throw new CommonException(ClusterCheckConstant.ERROR_DELETE_NODE_ROLE_FAILED);
        }
        if (ClusterNodeRoleEnum.isEtcd(roleId)
                && Boolean.FALSE.equals(ClusterNodeRoleEnum.isEtcdAndWorker(devopsClusterNodeDTO.getRole()))
                && Boolean.FALSE.equals(ClusterNodeRoleEnum.isMasterAndEtcdAndWorker(devopsClusterNodeDTO.getRole()))
                && Boolean.FALSE.equals(ClusterNodeRoleEnum.isMasterAndEtcd(devopsClusterNodeDTO.getRole()))) {
            throw new CommonException(ClusterCheckConstant.ERROR_DELETE_NODE_ROLE_FAILED);
        }
        if (ClusterNodeRoleEnum.isMaster(roleId)
                && Boolean.FALSE.equals(ClusterNodeRoleEnum.isMasterAndEtcd(devopsClusterNodeDTO.getRole()))
                && Boolean.FALSE.equals(ClusterNodeRoleEnum.isMaster(devopsClusterNodeDTO.getRole()))
                && Boolean.FALSE.equals(ClusterNodeRoleEnum.isMasterAndWorker(devopsClusterNodeDTO.getRole()))
                && Boolean.FALSE.equals(ClusterNodeRoleEnum.isMasterAndEtcdAndWorker(devopsClusterNodeDTO.getRole()))) {
            throw new CommonException(ClusterCheckConstant.ERROR_DELETE_NODE_ROLE_FAILED);
        }
    }

    @Override
    public void installK8s(DevopsClusterInstallPayload devopsClusterInstallPayload) {
        DevopsClusterOperationRecordDTO devopsClusterOperationRecordDTO = devopsClusterOperationRecordMapper.selectByPrimaryKey(devopsClusterInstallPayload.getOperationRecordId());
        DevopsClusterDTO devopsClusterDTO = devopsClusterMapper.selectByPrimaryKey(devopsClusterInstallPayload.getClusterId());
        SSHClient ssh = new SSHClient();
        try {
            List<DevopsClusterNodeDTO> devopsClusterNodeDTOList = devopsClusterNodeMapper.listByClusterId(devopsClusterInstallPayload.getClusterId());
            InventoryVO inventoryVO = calculateGeneralInventoryValue(devopsClusterNodeDTOList);
            LOGGER.info(">>>>>>>>> [install k8s] clusterId {} :start to create ssh connection object <<<<<<<<<", devopsClusterInstallPayload.getClusterId());
            sshUtil.sshConnect(ConvertUtils.convertObject(devopsClusterInstallPayload.getHostConnectionVO(), HostConnectionVO.class), ssh);
            generateAndUploadNodeConfiguration(ssh, devopsClusterInstallPayload.getDevopsClusterReqVO().getCode(), inventoryVO);
            generateAndUploadAnsibleShellScript(ssh, devopsClusterInstallPayload.getDevopsClusterReqVO().getCode(), INSTALL_K8S, "/tmp/install.log", "/tmp/" + devopsClusterOperationRecordDTO.getId());
            ExecResultInfoVO resultInfoVO = sshUtil.execCommand(ssh, String.format(BACKGROUND_COMMAND_TEMPLATE, "/tmp/" + INSTALL_K8S, "/tmp/nohup-install"));
            LOGGER.info(">>>>>>>>> [install k8s] clusterId {} :execute install command in background <<<<<<<<<", devopsClusterInstallPayload.getClusterId());
            // 集群安装出现错误，设置错误消息并更新集群状态
            if (resultInfoVO.getExitCode() != 0) {
                devopsClusterOperationRecordDTO.setStatus(ClusterOperationStatusEnum.FAILED.value())
                        .appendErrorMsg(resultInfoVO.getStdOut() + "\n" + resultInfoVO.getStdErr());
                devopsClusterDTO.setStatus(ClusterStatusEnum.FAILED.value());
                devopsClusterOperationRecordMapper.updateByPrimaryKeySelective(devopsClusterOperationRecordDTO);
                devopsClusterMapper.updateByPrimaryKeySelective(devopsClusterDTO);
            }
            LOGGER.info(">>>>>>>>> [install k8s] clusterId {} :waiting for installing completed<<<<<<<<<", devopsClusterInstallPayload.getClusterId());
        } catch (Exception e) {
            devopsClusterOperationRecordDTO.setStatus(ClusterOperationStatusEnum.FAILED.value())
                    .appendErrorMsg(e.getMessage());
            devopsClusterDTO.setStatus(ClusterStatusEnum.FAILED.value());
            devopsClusterOperationRecordMapper.updateByPrimaryKeySelective(devopsClusterOperationRecordDTO);
            devopsClusterMapper.updateByPrimaryKeySelective(devopsClusterDTO);
            throw new CommonException(e.getMessage(), e);
        } finally {
            sshUtil.sshDisconnect(ssh);
        }
    }


    private void installAgent(DevopsClusterDTO devopsClusterDTO, DevopsClusterOperationRecordDTO devopsClusterOperationRecordDTO, SSHClient ssh) {
        try {
            ExecResultInfoVO helmInstallResult = sshUtil.execCommand(ssh, String.format(INSTALL_HELM_TEMPLATE, helmDownloadUrl));
            if (helmInstallResult.getExitCode() != 0) {
                devopsClusterOperationRecordDTO.appendErrorMsg(helmInstallResult.getStdOut() + "\n" + helmInstallResult.getStdErr());
            }
            String agentInstallCommand = devopsClusterService.getInstallString(devopsClusterDTO, "");
            ExecResultInfoVO agentInstallResult = sshUtil.execCommand(ssh, agentInstallCommand);
            if (agentInstallResult.getExitCode() != 0) {
                devopsClusterOperationRecordDTO.appendErrorMsg(agentInstallResult.getStdOut() + "\n" + agentInstallResult.getStdErr());
            }
        } catch (Exception e) {
            devopsClusterOperationRecordDTO.appendErrorMsg(e.getMessage());
        }
    }

    @Override
    public List<DevopsClusterNodeDTO> queryByClusterId(Long clusterId) {
        Assert.notNull(clusterId, ClusterCheckConstant.ERROR_CLUSTER_ID_IS_NULL);
        DevopsClusterNodeDTO devopsClusterNodeDTO = new DevopsClusterNodeDTO();
        devopsClusterNodeDTO.setClusterId(clusterId);
        return devopsClusterNodeMapper.select(devopsClusterNodeDTO);
    }

    @Override
    public void addNode(Long projectId, Long clusterId, DevopsClusterNodeVO nodeVO) {
        Assert.notNull(projectId, ResourceCheckConstant.ERROR_PROJECT_ID_IS_NULL);
        Assert.notNull(clusterId, ClusterCheckConstant.ERROR_CLUSTER_ID_IS_NULL);
        nodeVO.setProjectId(projectId);

        // 获取锁,失败则抛出异常，成功则程序继续
        LOGGER.info(">>>>>>>>> [add node] check cluster {} is operating. <<<<<<<<<<<<<<<", clusterId);
        String lockKey = String.format(CLUSTER_LOCK_KEY, clusterId);
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "lock", 10, TimeUnit.MINUTES))) {
            throw new CommonException(ClusterCheckConstant.ERROR_CLUSTER_STATUS_IS_OPERATING);
        }
        // 更新redis集群操作状态
        LOGGER.info(">>>>>>>>> [add node] cache cluster {} operating record. <<<<<<<<<<<<<<<", clusterId);
        DevopsClusterOperatorVO devopsClusterOperatorVO = new DevopsClusterOperatorVO();
        devopsClusterOperatorVO.setClusterId(clusterId);
        devopsClusterOperatorVO.setOperating(ClusterOperatingTypeEnum.ADD_NODE.value());
        devopsClusterOperatorVO.setStatus(ClusterStatusEnum.OPERATING.value());
        String operatingKey = String.format(CLUSTER_OPERATING_KEY, clusterId);
        stringRedisTemplate.opsForValue().set(operatingKey, JsonHelper.marshalByJackson(devopsClusterOperatorVO), 10, TimeUnit.MINUTES);

        devopsClusterNodeOperatorService.addNode(projectId, clusterId, nodeVO, lockKey, operatingKey);

    }

    @Override
    public DevopsClusterNodeDTO queryByClusterIdAndNodeName(Long clusterId, String nodeName) {
        Assert.notNull(clusterId, ClusterCheckConstant.ERROR_CLUSTER_ID_IS_NULL);
        Assert.notNull(nodeName, ClusterCheckConstant.ERROR_NODE_NAME_IS_NULL);
        DevopsClusterNodeDTO devopsClusterNodeDTO = new DevopsClusterNodeDTO();
        devopsClusterNodeDTO.setClusterId(clusterId);
        devopsClusterNodeDTO.setName(nodeName);
        return devopsClusterNodeMapper.selectOne(devopsClusterNodeDTO);
    }

    @Override
    public List<DevopsClusterNodeDTO> queryNodeByClusterIdAndType(Long clusterId, ClusterNodeTypeEnum type) {
        Assert.notNull(clusterId, ClusterCheckConstant.ERROR_CLUSTER_ID_IS_NULL);
        Assert.notNull(type, ClusterCheckConstant.ERROR_CLUSTER_TYPE_IS_OPERATING);
        DevopsClusterNodeDTO devopsClusterNodeDTO = new DevopsClusterNodeDTO();
        devopsClusterNodeDTO.setClusterId(clusterId);
        devopsClusterNodeDTO.setType(type.getType());
        return devopsClusterNodeMapper.select(devopsClusterNodeDTO);
    }


    @Override
    public Long saveInfo(List<DevopsClusterNodeDTO> devopsClusterDTOList, Long projectId, DevopsClusterReqVO devopsClusterReqVO) {
        DevopsClusterDTO devopsClusterDTO = devopsClusterService.insertClusterInfo(projectId, devopsClusterReqVO, ClusterTypeEnum.CREATED.value());
        List<DevopsClusterNodeDTO> devopsClusterNodeDTOS = devopsClusterDTOList.stream()
                .peek(n -> {
                    n.setClusterId(devopsClusterDTO.getId());
                    n.setProjectId(projectId);
                })
                .collect(Collectors.toList());
        batchInsert(devopsClusterNodeDTOS);
        return devopsClusterDTO.getId();
    }

    @Override
    public DevopsClusterInstallPayload checkAndSaveNode(DevopsClusterInstallPayload devopsClusterInstallPayload) {
        // 项目id
        Long projectId = devopsClusterInstallPayload.getProjectId();
        // 操作记录id
        String redisKey = devopsClusterInstallPayload.getRedisKey();

        SSHClient ssh = new SSHClient();
        DevopsNodeCheckResultVO devopsNodeCheckResultVO = new DevopsNodeCheckResultVO();
        try {
            try {
                LOGGER.info(">>>>>>>>> [check node] key {} :start to create ssh connection object <<<<<<<<<", redisKey);
                sshUtil.sshConnect(devopsClusterInstallPayload.getHostConnectionVO(), ssh);
            } catch (IOException e) {
                throw new Exception(String.format("Failed to connect to host: [ %s ] by ssh", devopsClusterInstallPayload.getHostConnectionVO().getHostIp()));
            }
            // 安装docker
            try {
                LOGGER.info(">>>>>>>>> [check node] key {} :start to install docker <<<<<<<<<", redisKey);
                ExecResultInfoVO resultInfoVO = sshUtil.execCommand(ssh, INSTALL_DOCKER_COMMAND);
                if (resultInfoVO != null && resultInfoVO.getExitCode() != 0) {
                    throw new Exception(String.format("Failed to install docker on host: [ %s ],error is :%s", ssh.getRemoteHostname(), resultInfoVO.getStdErr()));
                }
            } catch (IOException e) {
                throw new Exception(String.format("Failed to exec command [ %s ] on host [ %s ],error is :%s", INSTALL_DOCKER_COMMAND, ssh.getRemoteHostname(), e.getMessage()));
            }
            // 生成相关配置节点
            InventoryVO inventoryVO = calculateGeneralInventoryValue(devopsClusterInstallPayload.getDevopsClusterNodeToSaveDTOList());
            // 上传配置文件
            generateAndUploadNodeConfiguration(ssh, devopsClusterInstallPayload.getDevopsClusterReqVO().getCode(), inventoryVO);
            // 执行检测命令
            LOGGER.info(">>>>>>>>> [check node] start to check node <<<<<<<<<");
            // 检查节点，如果返回错误，抛出错误
            String errorMsg = checkAndSave(ssh, devopsNodeCheckResultVO, redisKey);
            if (!StringUtils.isEmpty(errorMsg)) {
                throw new Exception(errorMsg);
            }
            LOGGER.info(">>>>>>>>> [check node] check node complete <<<<<<<<<");
            // 节点检查通过，保存节点信息
            Long clusterId = saveInfo(devopsClusterInstallPayload.getDevopsClusterNodeToSaveDTOList(), projectId, devopsClusterInstallPayload.getDevopsClusterReqVO());
            devopsClusterInstallPayload.setClusterId(clusterId);
            return devopsClusterInstallPayload;
        } catch (Exception e) {
            devopsNodeCheckResultVO.setErrorMsg(e.getMessage())
                    .setStatus(ClusterOperationStatusEnum.FAILED.value());
            stringRedisTemplate.opsForValue().getAndSet(redisKey, JsonHelper.marshalByJackson(devopsNodeCheckResultVO));
            throw new CommonException(e.getMessage(), e);
        } finally {
            sshUtil.sshDisconnect(ssh);
        }
    }

    public String checkAndSave(SSHClient ssh, DevopsNodeCheckResultVO devopsNodeCheckResultVO, String redisKey) {
        try {
            String errorMsg;
            // 配置检查
            ExecResultInfoVO resultInfoVOForVariable = sshUtil.execCommand(ssh, String.format(ANSIBLE_COMMAND_TEMPLATE, VARIABLE));
            if (resultInfoVOForVariable.getExitCode() != 0) {
                errorMsg = resultInfoVOForVariable.getStdOut() + "\n" + resultInfoVOForVariable.getStdErr();
                devopsNodeCheckResultVO.setStatus(CommandStatus.FAILED.getStatus());
                devopsNodeCheckResultVO.getConfiguration().setStatus(ClusterOperationStatusEnum.FAILED.value())
                        .setErrorMessage(errorMsg);
                stringRedisTemplate.opsForValue().getAndSet(redisKey, JsonHelper.marshalByJackson(devopsNodeCheckResultVO));
                return errorMsg;
            } else {
                devopsNodeCheckResultVO.getConfiguration().setStatus(ClusterOperationStatusEnum.SUCCESS.value());
            }
            stringRedisTemplate.opsForValue().getAndSet(redisKey, JsonHelper.marshalByJackson(devopsNodeCheckResultVO));

            // 节点系统检查
            ExecResultInfoVO resultInfoVOForSystem = sshUtil.execCommand(ssh, String.format(ANSIBLE_COMMAND_TEMPLATE, SYSTEM));
            if (resultInfoVOForSystem.getExitCode() != 0) {
                errorMsg = resultInfoVOForSystem.getStdOut() + "\n" + resultInfoVOForSystem.getStdErr();
                devopsNodeCheckResultVO.setStatus(CommandStatus.FAILED.getStatus());
                devopsNodeCheckResultVO.getConfiguration().setStatus(ClusterOperationStatusEnum.FAILED.value())
                        .setErrorMessage(errorMsg);
                stringRedisTemplate.opsForValue().getAndSet(redisKey, JsonHelper.marshalByJackson(devopsNodeCheckResultVO));
                return errorMsg;
            } else {
                devopsNodeCheckResultVO.getSystem().setStatus(ClusterOperationStatusEnum.SUCCESS.value());
            }
            stringRedisTemplate.opsForValue().getAndSet(redisKey, JsonHelper.marshalByJackson(devopsNodeCheckResultVO));

            // 内存检查
            ExecResultInfoVO resultInfoVOForMemory = sshUtil.execCommand(ssh, String.format(ANSIBLE_COMMAND_TEMPLATE, MEMORY));
            if (resultInfoVOForMemory.getExitCode() != 0) {
                errorMsg = resultInfoVOForMemory.getStdOut() + "\n" + resultInfoVOForMemory.getStdErr();
                devopsNodeCheckResultVO.setStatus(CommandStatus.FAILED.getStatus());
                devopsNodeCheckResultVO.getConfiguration().setStatus(ClusterOperationStatusEnum.FAILED.value())
                        .setErrorMessage(errorMsg);
                stringRedisTemplate.opsForValue().getAndSet(redisKey, JsonHelper.marshalByJackson(devopsNodeCheckResultVO));
                return errorMsg;
            } else {
                devopsNodeCheckResultVO.getMemory().setStatus(ClusterOperationStatusEnum.SUCCESS.value());
            }
            stringRedisTemplate.opsForValue().getAndSet(redisKey, JsonHelper.marshalByJackson(devopsNodeCheckResultVO));

            // CPU检查
            ExecResultInfoVO resultInfoVOForCPU = sshUtil.execCommand(ssh, String.format(ANSIBLE_COMMAND_TEMPLATE, CPU));
            if (resultInfoVOForCPU.getExitCode() != 0) {
                errorMsg = resultInfoVOForCPU.getStdOut() + "\n" + resultInfoVOForCPU.getStdErr();
                devopsNodeCheckResultVO.setStatus(CommandStatus.FAILED.getStatus());
                devopsNodeCheckResultVO.getConfiguration().setStatus(ClusterOperationStatusEnum.FAILED.value())
                        .setErrorMessage(errorMsg);
                stringRedisTemplate.opsForValue().getAndSet(redisKey, JsonHelper.marshalByJackson(devopsNodeCheckResultVO));
                return errorMsg;
            } else {
                devopsNodeCheckResultVO.getCpu().setStatus(ClusterOperationStatusEnum.SUCCESS.value());
                // CPU作为最后一步检查成功，代表整个variable、system、memory、CPU检查成功，暂时将状态置为SUCCESS，因为后续处理可能会失败，将状态重新置为FAILED
                devopsNodeCheckResultVO.setStatus(ClusterOperationStatusEnum.SUCCESS.value());
            }
            stringRedisTemplate.opsForValue().getAndSet(redisKey, JsonHelper.marshalByJackson(devopsNodeCheckResultVO));
            return null;
        } catch (Exception e) {
            throw new CommonException(e.getMessage());
        }
    }

    @Override
    public void generateAndUploadNodeConfiguration(SSHClient ssh, String suffix, InventoryVO inventoryVO) {
        String configValue = generateInventoryInI(inventoryVO);
        String filePath = String.format(ANSIBLE_CONFIG_BASE_DIR_TEMPLATE, suffix) + System.getProperty("file.separator") + "inventory.ini";
        String targetFilePath = ANSIBLE_CONFIG_TARGET_BASE_DIR + System.getProperty("file.separator") + "inventory.ini";
        FileUtil.saveDataToFile(filePath, configValue);
        sshUtil.uploadFile(ssh, filePath, targetFilePath);
    }

    @Override
    public void generateAndUploadAnsibleShellScript(SSHClient ssh, String suffix, String command, String logPath, String exitCodePath) {
        String configValue = generateShellScript(command, logPath, exitCodePath);
        String filePath = String.format(ANSIBLE_CONFIG_BASE_DIR_TEMPLATE, suffix) + System.getProperty("file.separator") + command;
        String targetFilePath = ANSIBLE_CONFIG_TARGET_BASE_DIR + System.getProperty("file.separator") + command;
        FileUtil.saveDataToFile(filePath, configValue);
        sshUtil.uploadFile(ssh, filePath, targetFilePath);
    }

    @Override
    public void update() {
        // 添加redis锁，防止多个pod重复执行
        try {
            if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(CLUSTER_STATUS_SYNC_REDIS_LOCK, "lock", 3, TimeUnit.MINUTES))) {
                throw new CommonException(ClusterCheckConstant.ERROR_CLUSTER_STATUS_IS_OPERATING);
            }
            DevopsClusterOperationRecordDTO devopsClusterOperationRecordDTO = new DevopsClusterOperationRecordDTO()
                    .setStatus(ClusterOperationStatusEnum.OPERATING.value())
                    .setType(ClusterOperationTypeEnum.INSTALL_K8S.getType());
            List<DevopsClusterOperationRecordDTO> devopsClusterOperationRecordDTOList = devopsClusterOperationRecordMapper.select(devopsClusterOperationRecordDTO);
            if (CollectionUtils.isEmpty(devopsClusterOperationRecordDTOList)) {
                return;
            }
            List<Long> clusterIds = devopsClusterOperationRecordDTOList.stream().map(DevopsClusterOperationRecordDTO::getClusterId).collect(Collectors.toList());
            Map<Long, DevopsClusterDTO> devopsClusterDTOMap = devopsClusterMapper.listByClusterIds(clusterIds)
                    .stream()
                    .collect(Collectors.toMap(DevopsClusterDTO::getId, d -> d));
            for (DevopsClusterOperationRecordDTO record : devopsClusterOperationRecordDTOList) {
                Long clusterId = record.getClusterId();
                LOGGER.info(">>>>>>>>> [update cluster status] clusterId:{} operationId:{} <<<<<<<<<", clusterId, record.getId());
                DevopsClusterDTO devopsClusterDTO = devopsClusterDTOMap.get(clusterId);
                if (devopsClusterDTO == null) {
                    devopsClusterOperationRecordMapper.deleteByPrimaryKey(record.getId());
                    continue;
                }
                if (!ClusterStatusEnum.OPERATING.value().equalsIgnoreCase(devopsClusterDTO.getStatus())) {
                    if (ClusterStatusEnum.FAILED.value().equalsIgnoreCase(devopsClusterDTO.getStatus())) {
                        record.setStatus(ClusterOperationStatusEnum.FAILED.value());
                    } else {
                        record.setStatus(ClusterOperationStatusEnum.SUCCESS.value());
                    }
                    devopsClusterOperationRecordMapper.updateByPrimaryKeySelective(record);
                    continue;
                }
                SSHClient ssh = new SSHClient();
                try {
                    List<DevopsClusterNodeDTO> devopsClusterNodeDTOList = devopsClusterNodeMapper.listByClusterId(clusterId);
                    List<DevopsClusterNodeDTO> devopsClusterOutterNodeDTOList = devopsClusterNodeDTOList.stream().filter(n -> ClusterNodeTypeEnum.OUTTER.getType().equalsIgnoreCase(n.getType())).collect(Collectors.toList());
                    if (!CollectionUtils.isEmpty(devopsClusterOutterNodeDTOList)) {
                        sshUtil.sshConnect(ConvertUtils.convertObject(devopsClusterOutterNodeDTOList.get(0), HostConnectionVO.class), ssh);
                    } else {
                        sshUtil.sshConnect(ConvertUtils.convertObject(devopsClusterNodeDTOList.get(0), HostConnectionVO.class), ssh);
                    }
                    ExecResultInfoVO resultInfoVO = sshUtil.execCommand(ssh, String.format(CAT_FILE, record.getId()));
                    if (resultInfoVO.getExitCode() != 0) {
                        if (resultInfoVO.getStdErr().contains("No such file or directory")) {
                            LOGGER.info(">>>>>>>>> [update cluster status] cluster [ {} ] operation [ {} ] is installing <<<<<<<<<", clusterId, record.getId());
                        } else {
                            LOGGER.info(">>>>>>>>> [update cluster status] Failed to get install status of host [ {} ],error is: {} <<<<<<<<<", ssh.getRemoteHostname(), resultInfoVO.getStdErr());
                            record.setStatus(ClusterOperationStatusEnum.FAILED.value())
                                    .appendErrorMsg(resultInfoVO.getStdErr());
                            devopsClusterDTO.setStatus(ClusterStatusEnum.FAILED.value());
                            devopsClusterMapper.updateByPrimaryKeySelective(devopsClusterDTO);
                            devopsClusterOperationRecordMapper.updateByPrimaryKeySelective(record);
                        }
                    } else {
                        if ("0".equals(resultInfoVO.getStdOut().replaceAll("\r|\n", ""))) {
                            // k8s安装成功
                            LOGGER.info(">>>>>>>>> [update cluster status] cluster [ {} ] operation [ {} ] install success <<<<<<<<<", clusterId, record.getId());
                            record.setStatus(ClusterOperationStatusEnum.SUCCESS.value());
                            devopsClusterDTO.setStatus(ClusterStatusEnum.DISCONNECT.value());
                            // 安装agent, 第一步安装helm ，第二步安装agent。这一步骤如果出现错误,只保存错误信息
                            installAgent(devopsClusterDTO, record, ssh);
                        } else {
                            LOGGER.info(">>>>>>>>> [update cluster status] ccluster [ {} ] operation [ {} ] install failed <<<<<<<<<", clusterId, record.getId());
                            record.setStatus(ClusterOperationStatusEnum.FAILED.value());
                            devopsClusterDTO.setStatus(ClusterStatusEnum.FAILED.value());
                            record.setErrorMsg(String.format(">>>>>>>>> [update cluster status] login node [ %s ] and cat /tmp/install.log for more info <<<<<<<<<", ssh.getRemoteHostname()));
                        }
                        devopsClusterMapper.updateByPrimaryKeySelective(devopsClusterDTO);
                        devopsClusterOperationRecordMapper.updateByPrimaryKeySelective(record);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    sshUtil.sshDisconnect(ssh);
                }
            }
        } finally {
            stringRedisTemplate.delete(CLUSTER_STATUS_SYNC_REDIS_LOCK);
        }
    }

    private String generateInventoryInI(InventoryVO inventoryVO) {
        Map<String, String> map = new HashMap<>();
        map.put("{{all}}", inventoryVO.getAll().toString());
        map.put("{{etcd}}", inventoryVO.getEtcd().toString());
        map.put("{{kube-master}}", inventoryVO.getKubeMaster().toString());
        map.put("{{kube-worker}}", inventoryVO.getKubeWorker().toString());
        map.put("{{new-master}}", inventoryVO.getNewMaster().toString());
        map.put("{{new-worker}}", inventoryVO.getNewWorker().toString());
        map.put("{{new-etcd}}", inventoryVO.getNewEtcd().toString());
        map.put("{{del-worker}}", inventoryVO.getDelWorker().toString());
        map.put("{{del-master}}", inventoryVO.getDelMaster().toString());
        map.put("{{del-etcd}}", inventoryVO.getDelEtcd().toString());
        map.put("{{del-node}}", inventoryVO.getDelNode().toString());
        InputStream inventoryIniInputStream = DevopsClusterNodeServiceImpl.class.getResourceAsStream("/template/inventory.ini");

        return FileUtil.replaceReturnString(inventoryIniInputStream, map);
    }

    private String generateShellScript(String command, String logPath, String exitCodePath) {
        Map<String, String> param = new HashMap<>();
        param.put("{{command}}", command);
        param.put("{{log-path}}", logPath);
        param.put("{{exit-code-path}}", exitCodePath);
        InputStream shellInputStream = DevopsClusterNodeServiceImpl.class.getResourceAsStream("/shell/ansible.sh");
        return FileUtil.replaceReturnString(shellInputStream, param);
    }
}
