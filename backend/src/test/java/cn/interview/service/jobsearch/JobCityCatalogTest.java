package cn.interview.service.jobsearch;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class JobCityCatalogTest {
    @Test void allOptionIsFirstAndDoesNotReuseAConcreteCityCode(){
        var catalog=new JobCityCatalog();
        assertThat(catalog.cities().getFirst()).containsEntry("id","all").containsEntry("name","所有");
        assertThat(catalog.name("all")).isEqualTo("所有");
        assertThat(catalog.sourceCode("all","BOSS")).isEqualTo("100010000");
        assertThat(catalog.sourceCode("all","LIEPIN")).isEqualTo("410");
        assertThat(catalog.sourceCode("all","ZHAOPIN")).isEqualTo("489");
    }

    @Test void onlySevenVerifiedCitiesUseTheirOwnCodesAcrossEverySource(){
        var catalog=new JobCityCatalog();
        var expected=List.of(
            List.of("shenzhen","深圳","101280600","050090","765"),
            List.of("beijing","北京","101010100","010","530"),
            List.of("shanghai","上海","101020100","020","538"),
            List.of("guangzhou","广州","101280100","050020","763"),
            List.of("hangzhou","杭州","101210100","070020","653"),
            List.of("suzhou","苏州","101190400","060080","639"),
            List.of("chengdu","成都","101270100","280020","801")
        );
        assertThat(catalog.cities()).hasSize(8);
        assertThat(catalog.cities().subList(1,catalog.cities().size())).extracting(c->c.get("id")).containsExactlyElementsOf(expected.stream().map(c->c.get(0)).toList());
        for(var city:expected){
            assertThat(catalog.name(city.get(0))).isEqualTo(city.get(1));
            assertThat(catalog.sourceCode(city.get(0),"BOSS")).isEqualTo(city.get(2));
            assertThat(catalog.sourceCode(city.get(0),"LIEPIN")).isEqualTo(city.get(3));
            assertThat(catalog.sourceCode(city.get(0),"ZHAOPIN")).isEqualTo(city.get(4));
        }
        assertThatThrownBy(()->catalog.sourceCode("unknown","BOSS")).hasMessageContaining("城市");
        assertThatThrownBy(()->catalog.sourceCode("beijing","OTHER")).hasMessageContaining("来源");
    }
}
