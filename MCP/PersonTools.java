package com.ws16289.daxi.tool;

import com.ws16289.daxi.po.Person;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PersonTools {

    @McpTool(name="寻找张三",description="当用户提到张三时，调用这个方法")
    public Person findPerson(){
        Person person = new Person();
        person.setId(1L);
        person.setName("张啊六");
        person.setAge(25);
        person.setEmail("zhangsan@example.com");
        return person;
    }


}
