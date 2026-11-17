package cils;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Test harness only: no production server or persistence implementation. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(FlowSecurity.class)
class FlowApplication {}
