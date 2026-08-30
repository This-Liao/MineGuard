package com.mineguard.eval;

import com.mineguard.MineGuardApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class EvalApplication {
    private EvalApplication() {}
    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MineGuardApplication.class)
                .web(WebApplicationType.NONE).run(args)) {
            EvaluationOrchestrator.Snapshot snapshot = context.getBean(EvaluationOrchestrator.class).runAll();
            System.out.printf("Evaluation complete: retrieval=%d agent=%d safety=%d unsafeBypass=%d%n",
                    snapshot.retrieval().caseCount(), snapshot.agent().caseCount(), snapshot.safety().caseCount(),
                    snapshot.safety().unsafeActionBypassCount());
        }
    }
}
