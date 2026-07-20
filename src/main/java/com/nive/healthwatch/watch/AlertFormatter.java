package com.nive.healthwatch.watch;

import com.nive.healthwatch.domain.AnomalyEvent;
import com.nive.healthwatch.notify.AlertMessage;
import org.springframework.stereotype.Component;

/**
 * @author nive
 * @class AlertFormatter
 * @desc anomaly + (선택)AI 요약을 계획서의 알림 포맷으로 렌더링한다.
 * @since 2026-07-06
 */
@Component
public class AlertFormatter {

    public AlertMessage anomaly(AnomalyEvent e, String aiSummary) {
        String title = "[" + e.getSeverity() + "] " + e.getServiceName() + " health anomaly detected";
        StringBuilder body = new StringBuilder();
        body.append("[프로젝트]\n").append(e.getServiceName()).append('\n');
        body.append("\n[감지 조건]\n- ").append(nz(e.getTriggerSummary())).append('\n');
        if (e.getCurrentJson() != null) {
            body.append("\n[현재 지표]\n").append(e.getCurrentJson()).append('\n');
        }
        if (aiSummary != null && !aiSummary.isBlank()) {
            body.append("\n").append(aiSummary.trim()).append('\n');
        } else {
            body.append("\n[장애 원인]\nAI 분석 미수행/실패 — rule 기준 알림입니다.\n");
            body.append("\n[영향 범위]\n데이터 부족. 해당 프로젝트의 health 상태를 먼저 확인해야 합니다.\n");
            body.append("\n[확인 항목]\n1. /health, /health/detail 응답 상태 확인\n2. 최근 배포/네트워크/DB 연결 상태 확인\n");
        }
        return AlertMessage.alert(e.getId(), title, body.toString(), e.getServiceName());
    }

    public AlertMessage recovery(AnomalyEvent e) {
        String title = "[resolved] " + e.getServiceName() + " recovered";
        String body = "[프로젝트]\n" + e.getServiceName()
                + "\n\n[복구 조건]\n" + e.getTriggerType()
                + (e.getDbName() != null ? " (" + e.getDbName() + ")" : "")
                + "\n" + nz(e.getTriggerSummary()) + "\n조건이 해소되었습니다.";
        return AlertMessage.recovery(e.getId(), title, body, e.getServiceName());
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
