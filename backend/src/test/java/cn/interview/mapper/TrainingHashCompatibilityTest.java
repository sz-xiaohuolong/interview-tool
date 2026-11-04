package cn.interview.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class TrainingHashCompatibilityTest {
    @TempDir Path root;
    @Test void 固定历史样本摘要保持对象键排序和数组顺序() throws Exception {
        var json=new ObjectMapper();var db=new TrainingStore(new SqliteStore(root.toString(),json),new MockEnvironment());
        var nested=json.readTree("{\"z\":true,\"a\":[{\"y\":\"中文\",\"x\":null},2,1]}");
        assertThat(db.hash(nested)).isEqualTo("98e1dff1722662a2c0eb916a453c8c6a147e94fbbdd4d25019d04a526d3b3dfa");
        assertThat(db.hash(json.readTree("{\"a\":[{\"x\":null,\"y\":\"中文\"},2,1],\"z\":true}"))).isEqualTo("98e1dff1722662a2c0eb916a453c8c6a147e94fbbdd4d25019d04a526d3b3dfa");
        assertThat(db.hash(json.readTree("{\"a\":[{\"x\":null,\"y\":\"中文\"},1,2],\"z\":true}"))).isNotEqualTo("98e1dff1722662a2c0eb916a453c8c6a147e94fbbdd4d25019d04a526d3b3dfa");
        var protocol=json.readTree("{\"returnedModel\":null,\"requestedModel\":\"EXPLICIT_MOCK_V2\",\"promptVersion\":\"v2.1\",\"parameters\":{\"temperature\":0.4,\"max_tokens\":1200},\"mock\":true}");
        assertThat(db.hash(protocol)).isEqualTo("234b5b83048fd7806eb232c11ff1ebe50fdaa2f1f31cbc94eda23477abec9167");
        assertThat(nested.fieldNames()).toIterable().containsExactly("z","a");
    }
}
