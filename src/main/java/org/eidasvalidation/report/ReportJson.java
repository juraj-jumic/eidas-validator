package org.eidasvalidation.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** JSON serialisation of {@link Report}. */
public final class ReportJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ReportJson() {
    }

    public static String write(Report report) {
        try {
            return MAPPER.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise report", e);
        }
    }

    /** JSON without the bulky engine XML, for embedding in the HTML report. */
    public static String writeWithoutEngineReport(Report report) {
        return write(new Report(report.reportVersion(), report.language(), report.file(), report.validationTime(),
                report.online(), report.engine(), report.trust(), report.verdict(), report.signatures(), null));
    }
}
