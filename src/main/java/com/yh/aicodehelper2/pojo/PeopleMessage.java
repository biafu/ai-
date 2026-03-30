package com.yh.aicodehelper2.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeopleMessage {
    private String name;
    private int  age;
    private String sex;
    private String message;
}
