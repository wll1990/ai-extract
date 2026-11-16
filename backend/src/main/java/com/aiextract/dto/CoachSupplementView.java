package com.aiextract.dto;

import java.util.List;

public record CoachSupplementView(
        String status,
        String message,
        boolean shareTranscript,
        /** 当前用户就是该分身的教练所有者：此时无需发请求，直接去教练工作台补充 */
        boolean selfCoach,
        List<CoachNoteView> notes
) {
}
