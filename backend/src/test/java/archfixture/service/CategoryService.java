package archfixture.service;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fixture: shares its simple name with a service that has a reviewed raw 404 ({@code
 * CategoryService#requireEditor}), but adds a new, unreviewed object lookup that bypasses the
 * audited denial. The guard must reject it - a reviewed method never exempts its whole class.
 */
public class CategoryService {

  public Object findCategory(Map<UUID, Object> categories, UUID requestedId) {
    Object category = categories.get(requestedId);
    if (category == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
    }
    return category;
  }
}
