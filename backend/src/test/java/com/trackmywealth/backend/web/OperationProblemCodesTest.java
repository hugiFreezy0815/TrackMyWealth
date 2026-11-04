package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.web.Paged.SortField;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/** US-29-04 (#374): which problem codes an endpoint's mapping implies. */
class OperationProblemCodesTest {

  @Test
  void everyEndpointCanFailUnexpectedly() {
    assertThat(implied(Things.class, "plain"))
        .containsExactly(ProblemCode.UNAUTHENTICATED, ProblemCode.INTERNAL);
  }

  @Test
  void aPathVariableImpliesNotFoundAndAMalformedRequest() {
    assertThat(implied(Things.class, "one"))
        .contains(ProblemCode.NOT_FOUND, ProblemCode.VALIDATION_FAILED);
  }

  @Test
  void ifMatchImpliesTheVersionCodes() {
    assertThat(implied(Things.class, "replace"))
        .contains(
            ProblemCode.VERSION_REQUIRED,
            ProblemCode.VERSION_CONFLICT,
            ProblemCode.VALIDATION_FAILED,
            ProblemCode.NOT_FOUND);
  }

  @Test
  void aWriteWithoutIfMatchCanLoseARaceAndAnswersItWith409() {
    Method create = method(Things.class, "create");

    assertThat(OperationProblemCodes.implied(create)).contains(ProblemCode.VERSION_CONFLICT);
    assertThat(OperationProblemCodes.statusOf(create, ProblemCode.VERSION_CONFLICT))
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(OperationProblemCodes.documentedWithStatus(create))
        .containsEntry(ProblemCode.VERSION_CONFLICT, HttpStatus.CONFLICT);
  }

  @Test
  void aWriteWithIfMatchAnswersAVersionConflictWith412() {
    Method replace = method(Things.class, "replace");

    assertThat(OperationProblemCodes.documentedWithStatus(replace))
        .containsEntry(ProblemCode.VERSION_CONFLICT, HttpStatus.PRECONDITION_FAILED)
        .containsEntry(ProblemCode.VERSION_REQUIRED, HttpStatus.PRECONDITION_REQUIRED)
        .containsEntry(ProblemCode.NOT_FOUND, HttpStatus.NOT_FOUND);
  }

  @Test
  void aReadCannotLoseAVersionRace() {
    assertThat(implied(Things.class, "one")).doesNotContain(ProblemCode.VERSION_CONFLICT);
    assertThat(implied(Things.class, "search")).doesNotContain(ProblemCode.VERSION_CONFLICT);
  }

  @Test
  void everyKindOfWriteImpliesAVersionConflict() {
    assertThat(implied(Writes.class, "patch")).contains(ProblemCode.VERSION_CONFLICT);
    assertThat(implied(Writes.class, "delete")).contains(ProblemCode.VERSION_CONFLICT);
    assertThat(implied(Writes.class, "anyMethod")).doesNotContain(ProblemCode.VERSION_CONFLICT);
  }

  @Test
  void aPagedListImpliesInvalidSort() {
    assertThat(implied(Things.class, "list"))
        .contains(ProblemCode.INVALID_SORT, ProblemCode.VALIDATION_FAILED)
        .doesNotContain(ProblemCode.NOT_FOUND);
  }

  @Test
  void anUploadImpliesTheSizeLimit() {
    assertThat(implied(Things.class, "upload"))
        .contains(ProblemCode.IMPORT_FILE_TOO_LARGE, ProblemCode.VALIDATION_FAILED);
  }

  @Test
  void aBodyOrAQueryParameterImpliesAMalformedRequest() {
    assertThat(implied(Things.class, "create")).contains(ProblemCode.VALIDATION_FAILED);
    assertThat(implied(Things.class, "search")).contains(ProblemCode.VALIDATION_FAILED);
    assertThat(implied(Things.class, "form")).contains(ProblemCode.VALIDATION_FAILED);
  }

  @Test
  void ifMatchNamedByItsNameAttributeImpliesTheVersionCodesToo() {
    assertThat(implied(Things.class, "replaceByName"))
        .contains(ProblemCode.VERSION_REQUIRED, ProblemCode.VERSION_CONFLICT);
  }

  @Test
  void aMethodWithoutAMappingImpliesOnlyTheRequestHandlingAroundIt() {
    assertThat(implied(Unmapped.class, "helper"))
        .containsExactly(ProblemCode.UNAUTHENTICATED, ProblemCode.INTERNAL);
  }

  @Test
  void aPublicPathNeedsNoSignInAndARateLimitedOneDocumentsIt() {
    assertThat(implied(Auth.class, "login"))
        .contains(ProblemCode.RATE_LIMITED)
        .doesNotContain(ProblemCode.UNAUTHENTICATED);
    assertThat(implied(Auth.class, "other")).doesNotContain(ProblemCode.RATE_LIMITED);
  }

  @Test
  void anAdministrationPathImpliesForbidden() {
    assertThat(implied(Admin.class, "users"))
        .contains(ProblemCode.FORBIDDEN, ProblemCode.UNAUTHENTICATED);
    assertThat(implied(Things.class, "plain")).doesNotContain(ProblemCode.FORBIDDEN);
  }

  @Test
  void declaredAndImpliedMakeTheDocumentedCodes() {
    Method method = method(Things.class, "one");

    assertThat(OperationProblemCodes.declared(method))
        .containsExactly(ProblemCode.ACCOUNT_ARCHIVED);
    assertThat(OperationProblemCodes.documented(method))
        .containsAll(OperationProblemCodes.implied(method))
        .contains(ProblemCode.ACCOUNT_ARCHIVED);
    assertThat(OperationProblemCodes.declared(method(Things.class, "plain"))).isEmpty();
  }

  @Test
  void thePathJoinsTheClassMappingWithTheMethodMapping() {
    assertThat(OperationProblemCodes.paths(method(Things.class, "one")))
        .containsExactly("/api/v1/things/{id}");
    assertThat(OperationProblemCodes.paths(method(Things.class, "plain")))
        .containsExactly("/api/v1/things");
    assertThat(OperationProblemCodes.paths(method(Auth.class, "login")))
        .containsExactly("/api/v1/auth/login");
    assertThat(OperationProblemCodes.paths(method(Unmapped.class, "relative")))
        .containsExactly("/relative");
    assertThat(OperationProblemCodes.paths(method(Admin.class, "users")))
        .containsExactly("/api/v1/admin/users");
  }

  private static java.util.Set<ProblemCode> implied(Class<?> type, String name) {
    return OperationProblemCodes.implied(method(type, name));
  }

  private static Method method(Class<?> type, String name) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(method -> method.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @RequestMapping("/api/v1/things")
  static class Things {

    @GetMapping
    void plain() {}

    @ProblemCodes(ProblemCode.ACCOUNT_ARCHIVED)
    @GetMapping("/{id}")
    void one(@PathVariable String id) {}

    @PutMapping("/{id}")
    void replace(@PathVariable String id, @RequestHeader("If-Match") String ifMatch) {}

    @GetMapping("/paged")
    void list(@Paged(sort = @SortField(name = "name"), defaultSort = "name,asc") Pageable page) {}

    @PostMapping("/upload")
    void upload(MultipartFile file) {}

    @PostMapping
    void create(@RequestBody String body) {}

    @GetMapping("/search")
    void search(@RequestParam String q) {}

    @GetMapping("/form")
    void form(@ModelAttribute String filter) {}

    @PutMapping("/by-name/{id}")
    void replaceByName(@PathVariable String id, @RequestHeader(name = "If-Match") String ifMatch) {}
  }

  static class Auth {

    @PostMapping("/api/v1/auth/login")
    void login(@RequestBody String body) {}

    @GetMapping("/api/v1/auth/other")
    void other() {}
  }

  @RequestMapping("/api/v1/admin/")
  static class Admin {

    @GetMapping("users")
    void users() {}
  }

  @RequestMapping("/api/v1/writes")
  static class Writes {

    @PatchMapping("/{id}")
    void patch(@PathVariable String id) {}

    @DeleteMapping("/{id}")
    void delete(@PathVariable String id) {}

    // No method: Spring maps every one, and the contract has no operation to put a code on.
    @RequestMapping("/any")
    void anyMethod() {}
  }

  static class Unmapped {

    @GetMapping("relative")
    void relative() {}

    void helper() {}
  }
}
