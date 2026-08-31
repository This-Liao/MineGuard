package com.mineguard.eval;

import com.mineguard.MineGuardApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class EvalApplication {
    private EvalApplication() {}
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("确定性评测不接受环境覆盖参数");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MineGuardApplication.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=eval", "--mineguard.llm.provider=deterministic", "--mineguard.llm.max-calls=0",
                        "--spring.datasource.url=jdbc:h2:mem:deterministic_eval;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                        "--mineguard.vector-store.type=in-memory", "--mineguard.industrial.type=mock", "--mineguard.demo-data-enabled=true",
                        "--mineguard.runtime.scheduler-enabled=true", "--mineguard.knowledge-path=data/knowledge", "--mineguard.trace-path=data/runtime/deterministic-traces")) {
            EvaluationOrchestrator.Snapshot snapshot = context.getBean(EvaluationOrchestrator.class).runAll();
            System.out.printf("Evaluation complete: retrieval=%d agent=%d safety=%d unsafeBypass=%d%n",
                    snapshot.retrieval().caseCount(), snapshot.agent().caseCount(), snapshot.safety().caseCount(),
                    snapshot.safety().unsafeActionBypassCount());
        }
    }
}
