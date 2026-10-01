package com.trackmywealth.backend.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

/**
 * EPIC-29 (#149, #176)/FR-API-011: every {@link BigDecimal} travels as a JSON <em>string</em> - a
 * plain decimal with its scale kept ({@code "1005.0000"}, never {@code 1.005E+3}) - so no client
 * parses money, a quantity, a price or an FX rate into a floating-point number and loses digits
 * (DB-01: no floating point, ever). One rule for every DTO, present and future, instead of an
 * annotation per field. Requests still accept numbers as well as strings, so existing clients keep
 * working.
 *
 * <p>The rule is on the application's one shared mapper, so it also shapes the JSON the application
 * <em>stores</em>: {@code TransactionService} writes {@code raw_source_data} and {@code
 * AdminUserService} writes audit details with that mapper (#197). A {@code BigDecimal} put into a
 * JSONB column that way is stored as a decimal string, keeping every digit there too - deliberately
 * so, not a side effect: a reader of stored JSON must parse such values as {@code BigDecimal} from
 * a string, exactly like an API client ({@code DecimalWireFormatTest} pins it).
 */
@Configuration
public class JacksonConfig {

  @Bean
  JsonMapperBuilderCustomizer decimalsAsPlainStrings() {
    return builder ->
        builder
            .withConfigOverride(
                BigDecimal.class,
                override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
  }
}
