package com.xucai.user.domain.query;

import com.xucai.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "用户分页查询条件")
public class UserPageQuery extends PageQuery {
    @Schema(description = "账户状态")
    private Integer status;

    @Schema(description = "用户名称")
    private String name;

    @Schema(description = "手机号码")
    private String phone;
}
