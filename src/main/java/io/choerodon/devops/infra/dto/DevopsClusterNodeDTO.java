package io.choerodon.devops.infra.dto;

import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

import io.swagger.annotations.ApiModelProperty;
import org.hzero.starter.keyencrypt.core.Encrypt;

import io.choerodon.devops.infra.enums.ClusterNodeAccountTypeEnum;
import io.choerodon.mybatis.annotation.ModifyAudit;
import io.choerodon.mybatis.annotation.VersionAudit;
import io.choerodon.mybatis.domain.AuditDomain;

@ModifyAudit
@VersionAudit
@Table(name = "devops_cluster_node")
public class DevopsClusterNodeDTO extends AuditDomain {
    @Id
    @GeneratedValue
    private Long id;

    @ApiModelProperty("节点名称")
    private String name;

    @ApiModelProperty("节点操作状态/nullable")
    private String operatingStatus;

    /**
     * {@link io.choerodon.devops.infra.enums.ClusterNodeRole}
     */
    @ApiModelProperty("节点类型,master对应4，etcd对应2，worker对应1，多个类型用数字之和表示，比如master、etcd节点，用4+2之和6表示")
    private Integer role;

    @ApiModelProperty("项目id")
    private Long projectId;

    @Encrypt
    @ApiModelProperty("集群id")
    private Long clusterId;

    @ApiModelProperty("节点ip")
    private String hostIp;

    @ApiModelProperty("节点ssh的端口")
    private Integer hostPort;

    /**
     * {@link ClusterNodeAccountTypeEnum}
     */
    @ApiModelProperty("认证类型")
    private String accountType;

    @ApiModelProperty("用户名")
    private String username;

    @ApiModelProperty("密码/rsa秘钥")
    private String password;

    @ApiModelProperty("错误信息/nullable")
    private String errorMsg;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getRole() {
        return role;
    }

    public void setRole(Integer role) {
        this.role = role;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getHostIp() {
        return hostIp;
    }

    public void setHostIp(String hostIp) {
        this.hostIp = hostIp;
    }

    public Integer getHostPort() {
        return hostPort;
    }

    public void setHostPort(Integer hostPort) {
        this.hostPort = hostPort;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Long getClusterId() {
        return clusterId;
    }

    public void setClusterId(Long clusterId) {
        this.clusterId = clusterId;
    }

    public String getOperatingStatus() {
        return operatingStatus;
    }

    public void setOperatingStatus(String operatingStatus) {
        this.operatingStatus = operatingStatus;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    @Override
    public String toString() {
        return "DevopsClusterNodeDTO{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", operatingStatus='" + operatingStatus + '\'' +
                ", role=" + role +
                ", projectId=" + projectId +
                ", clusterId=" + clusterId +
                ", hostIp='" + hostIp + '\'' +
                ", hostPort=" + hostPort +
                ", accountType='" + accountType + '\'' +
                ", username='" + username + '\'' +
                ", password='" + password + '\'' +
                ", errorMsg='" + errorMsg + '\'' +
                '}';
    }
}