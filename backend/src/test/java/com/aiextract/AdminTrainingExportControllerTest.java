package com.aiextract;

import com.aiextract.controller.AdminTrainingExportController;
import com.aiextract.model.ExportJob;
import com.aiextract.service.TrainingExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出接口审计边界 —— P1-8。
 *
 * <p>轮询状态不能生成下载地址/写审计；只有 /download 才产生一次外发记录。</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminTrainingExportControllerTest {

    @Mock private TrainingExportService exportService;

    @Test
    void getDoesNotGenerateDownloadOrAudit() {
        UUID id = UUID.randomUUID();
        ExportJob job = ExportJob.builder().id(id).build();
        when(exportService.get(id)).thenReturn(job);
        when(exportService.toView(job)).thenReturn(Map.of("id", id.toString()));

        new AdminTrainingExportController(exportService).get(id);

        verify(exportService, never()).download(id);
    }

    @Test
    void downloadReturnsShortLivedUrlForOss() {
        UUID id = UUID.randomUUID();
        ExportJob job = ExportJob.builder().id(id).build();
        TrainingExportService.Download download = new TrainingExportService.Download(
                job, "https://oss.example/export.csv?sig=x", null);
        when(exportService.download(id)).thenReturn(download);

        ResponseEntity<?> response = new AdminTrainingExportController(exportService).download(id);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("url", "https://oss.example/export.csv?sig=x"), response.getBody());
    }
}
