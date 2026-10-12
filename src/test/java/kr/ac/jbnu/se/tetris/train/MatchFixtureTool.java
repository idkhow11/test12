package kr.ac.jbnu.se.tetris.train;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.ai.BaselineScorer;
import kr.ac.jbnu.se.tetris.core.Rules;

/**
 * fixtures/match/basic.json 을 만든다 (FIXTURES.md 5.10). {@code gradlew matchFixture} 로 실행한다.
 * <p>
 * 기준 AI 끼리 둔 짧은 대전 기록과, 턴마다 Java 인코더가 만든 모델 입력을 함께 적는다. Python 은 기록을 읽어 자기 인코더로
 * 같은 입력을 만들어야 한다(서로 다른 두 구현의 교차 확인). 기록은 {@link MatchReplay} 로 엔진과 맞는지 먼저 확인한다.
 * 기록 한 판과 턴 하나를 각각 한 줄로 적어, 파일이 너무 길어지지 않게 한다.
 */
public final class MatchFixtureTool {

	private static final ObjectMapper JSON = new ObjectMapper();

	private MatchFixtureTool() {
	}

	public static void main(String[] args) throws IOException {
		Path dir = Path.of(args[0]);
		Files.createDirectories(dir);
		BaselineScorer baseline = new BaselineScorer();
		MatchRunner.Player calm1 = new MatchRunner.Player("baseline-a", "neutral", "baseline", 0.3, baseline);
		MatchRunner.Player calm2 = new MatchRunner.Player("baseline-b", "neutral", "baseline", 0.3, baseline);
		MatchRunner.Player wild1 = new MatchRunner.Player("random-a", "neutral", "baseline", 50, baseline);
		MatchRunner.Player wild2 = new MatchRunner.Player("random-b", "neutral", "baseline", 50, baseline);

		StringBuilder sb = new StringBuilder();
		sb.append("{\n");
		sb.append("  \"rules_version\": ").append(JSON.writeValueAsString(Rules.VERSION)).append(",\n");
		sb.append("  \"type\": \"match\",\n");
		sb.append("  \"description\": \"블록 동기 대전 기록 match-1 과 턴마다의 모델 입력(형식 2). ")
				.append("Java 대전 러너로 만들었다(MatchFixtureTool, gradlew matchFixture). 손으로 고치지 않는다\",\n");
		sb.append("  \"source\": [\"java\"],\n");
		sb.append("  \"cases\": [\n");
		appendCase(sb, "baseline-cap", "기준 AI 끼리(τ = 0.3) 턴 상한 8 에 닿아 무승부",
				MatchRunner.play("fixture", 0, 7, 8, calm1, calm2));
		sb.append(",\n");
		appendCase(sb, "random-topout", "거의 무작위(τ = 50)로 두어 한쪽이 지는 판",
				MatchRunner.play("fixture", 0, 11, 300, wild1, wild2));
		sb.append("\n  ]\n}\n");
		Path file = dir.resolve("basic.json");
		Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
		System.out.println("만듦: " + file + " (" + Files.size(file) + " bytes)");
	}

	private static void appendCase(StringBuilder sb, String id, String description, ObjectNode match) throws IOException {
		List<ObjectNode> inputs = MatchReplay.check(match);
		sb.append("    {\n");
		sb.append("      \"id\": ").append(JSON.writeValueAsString(id)).append(",\n");
		sb.append("      \"description\": ").append(JSON.writeValueAsString(description + ", " + match.get("turns")
				+ "턴, 결과 " + match.get("result").asText())).append(",\n");
		sb.append("      \"match\": ").append(JSON.writeValueAsString(match)).append(",\n");
		sb.append("      \"expect\": {\n");
		sb.append("        \"inputs\": [\n");
		for (int t = 0; t < inputs.size(); t++) {
			sb.append("          ").append(JSON.writeValueAsString(inputs.get(t)));
			sb.append(t + 1 < inputs.size() ? ",\n" : "\n");
		}
		sb.append("        ]\n");
		sb.append("      }\n");
		sb.append("    }");
	}
}
