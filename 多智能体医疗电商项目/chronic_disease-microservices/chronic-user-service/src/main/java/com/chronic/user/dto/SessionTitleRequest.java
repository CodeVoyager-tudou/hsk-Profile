package com.chronic.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 会话标题请求体（创建/重命名会话）
 *
 * @author chronic
 */
@Data
public class SessionTitleRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话标题 */
    private String title;
}
