package com.rbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;;
import com.baomidou.mybatisplus.annotation.TableId;;
import com.baomidou.mybatisplus.annotation.TableName;;
import lombok.Data;;
import java.util.Date;;

@Data
@TableName("rbi_form")
public class FormDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String tableName;
    private String fieldsJson;
    private Long datasetId;
    private Date createTime;
}
