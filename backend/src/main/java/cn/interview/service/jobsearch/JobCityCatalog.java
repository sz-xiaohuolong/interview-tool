package cn.interview.service.jobsearch;

import cn.interview.exception.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JobCityCatalog {
    // 只列入三站真实样例已核实的城市；扩展前必须核实三站来源码。
    private record City(String id,String name,Map<String,String> codes){}
    private static final List<City> CITIES=List.of(
        new City("shenzhen","深圳",Map.of("BOSS","101280600","LIEPIN","050090","ZHAOPIN","765")),
        new City("beijing","北京",Map.of("BOSS","101010100","LIEPIN","010","ZHAOPIN","530")),
        new City("shanghai","上海",Map.of("BOSS","101020100","LIEPIN","020","ZHAOPIN","538")),
        new City("guangzhou","广州",Map.of("BOSS","101280100","LIEPIN","050020","ZHAOPIN","763")),
        new City("hangzhou","杭州",Map.of("BOSS","101210100","LIEPIN","070020","ZHAOPIN","653")),
        new City("suzhou","苏州",Map.of("BOSS","101190400","LIEPIN","060080","ZHAOPIN","639")),
        new City("chengdu","成都",Map.of("BOSS","101270100","LIEPIN","280020","ZHAOPIN","801"))
    );
    public List<Map<String,String>> cities(){return CITIES.stream().map(c->Map.of("id",c.id(),"name",c.name())).toList();}
    private City city(String id){return CITIES.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->ApiException.bad("请选择已支持的城市"));}
    public String name(String id){return city(id).name();}
    public String sourceCode(String id,String source){String code=city(id).codes().get(source);if(code==null)throw ApiException.bad("不支持的招聘来源");return code;}
}
