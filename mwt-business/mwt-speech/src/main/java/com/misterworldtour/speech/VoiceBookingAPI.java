package com.misterworldtour.speech;

import com.misterworldtour.speech.VoiceDialogClient.DialogSlots;
import com.misterworldtour.speech.VoiceDialogClient.Turn;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 음성 예약 (설계 이후 추가, 최종발표 시나리오 2.1). 대화로 상품·스타일·음료를 고르고 확인하면 신청까지 한다.
 * 신청은 화면 신청과 같은 검토(reviewApplication) → 등록(submitTravelApplication) 경로로 하므로 업무 규칙은 같다.
 * 로그인한 고객은 LoginSession 으로 확인한다.
 */
public interface VoiceBookingAPI {

    /** 대화 상태 (화면이 세션에 보관했다가 다음 말과 함께 넘긴다). applicationId 는 예약이 끝났으면 신청 번호. */
    record VoiceDialogState(List<Turn> history, DialogSlots slots, Long applicationId) implements Serializable {
        public VoiceDialogState {
            history = List.copyOf(history);
        }
    }

    /**
     * 한 턴의 결과. reply 는 소리 내어 읽을 대사, selection 은 지금까지 고른 내용(화면 표시용, 항목 → 값),
     * fallback 은 LLM 대신 규칙 대화로 답했는지.
     */
    record VoiceDialogTurn(VoiceDialogState state, String reply, boolean done, Long applicationId,
                           Map<String, String> selection, boolean fallback) {
    }

    /** 인사로 새 대화를 시작한다. */
    VoiceDialogTurn startVoiceBooking();

    /** 고객 말(브라우저가 인식한 문장) 한 마디를 처리한다. */
    VoiceDialogTurn talk(VoiceDialogState state, String transcript);
}
