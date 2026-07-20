package com.nive.healthwatch.collect;

/**
 * @author nive
 * @class HttpProbe
 * @desc 단일 endpoint 호출 결과. status/statusCode/body/elapsed 를 담는다.
 *       outcome 은 OK(2xx)/HTTP_ERROR(비2xx)/TIMEOUT/ERROR.
 * @since 2026-07-06
 */
public record HttpProbe(Outcome outcome, int statusCode, String body, long elapsedMs) {

    public enum Outcome { OK, HTTP_ERROR, TIMEOUT, ERROR }

    public static HttpProbe ok(int statusCode, String body, long elapsedMs) {
        return new HttpProbe(Outcome.OK, statusCode, body, elapsedMs);
    }

    public static HttpProbe httpError(int statusCode, String body, long elapsedMs) {
        return new HttpProbe(Outcome.HTTP_ERROR, statusCode, body, elapsedMs);
    }

    public static HttpProbe timeout(long elapsedMs) {
        return new HttpProbe(Outcome.TIMEOUT, 0, null, elapsedMs);
    }

    public static HttpProbe error(long elapsedMs) {
        return new HttpProbe(Outcome.ERROR, 0, null, elapsedMs);
    }

    public boolean is2xx() {
        return outcome == Outcome.OK;
    }
}
