/**
 * Test-only fixtures that deliberately break {@code ArchitectureTest}'s rules, for {@code
 * ArchitectureRulesBiteTest}. Outside {@code com.trackmywealth.backend} on purpose: Spring Boot
 * scans that package for JPA entities and components in every {@code @SpringBootTest}, and a
 * fixture entity there would be mapped for real.
 */
package archfixture;
