package com.letsblog.platform.config;

import com.letsblog.platform.service.ComputeDeviceException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ComputeDeviceException は自身の持つHTTPステータスとメッセージで返す(issue #1399)。 */
class GlobalExceptionHandlerComputeDeviceTest {

    @Test
    void 例外のステータスとメッセージをそのまま返す() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new MultipartProperties());

        var response = handler.handleComputeDevice(
                new ComputeDeviceException(HttpStatus.CONFLICT, "適用中です"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("適用中です", response.getBody().error());
    }
}
