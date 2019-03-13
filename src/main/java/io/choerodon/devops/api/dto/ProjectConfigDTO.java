package io.choerodon.devops.api.dto;

import java.io.Serializable;

/**
 * @author zongw.lee@gmail.com
 * @since 2019/03/13
 */
public class ProjectConfigDTO implements Serializable {

    private String url;

    private String userName;

    private String password;

    private String project;

    private String email;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
