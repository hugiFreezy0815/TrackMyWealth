package com.trackmywealth.backend.camt;

import java.util.ArrayList;
import java.util.List;

/**
 * One element of a subtree {@link CamtXmlReader} keeps (an account, a balance or an entry): its
 * local name, its {@code Ccy} attribute and its text, only for an element without children. Only
 * elements of the message's own namespace are kept; anything else is skipped while it is read.
 */
final class CamtNode {

  private final String localName;
  private final String ccy;
  private final boolean kept;
  private final List<CamtNode> childNodes = new ArrayList<>();
  // Bounded by CamtXmlReader.MAX_TEXT_LENGTH, as every text run is.
  private String ownText = "";
  private boolean partial;

  CamtNode(String localName, String ccy) {
    this(localName, ccy, true);
  }

  private CamtNode(String localName, String ccy, boolean kept) {
    this.localName = localName;
    this.ccy = ccy;
    this.kept = kept;
  }

  /** Stands for an element that is read past: it keeps no text and no children. */
  static CamtNode skipped() {
    return new CamtNode("", null, false);
  }

  boolean isKept() {
    return kept;
  }

  /** Marks that some of its descendants were not kept (an entry's surplus transactions). */
  void truncate() {
    partial = true;
  }

  boolean truncated() {
    return partial;
  }

  void add(CamtNode child) {
    // Mixed content is no part of a CAMT message: the blanks between children are layout.
    ownText = "";
    childNodes.add(child);
  }

  void appendText(char[] characters, int start, int length) {
    if (kept && childNodes.isEmpty()) {
      ownText = ownText.concat(new String(characters, start, length));
    }
  }

  /** The element's text, stripped; {@code null} when it has none or has children. */
  String value() {
    String stripped = ownText.strip();
    return stripped.isEmpty() ? null : stripped;
  }

  /** Its {@code Ccy} attribute, stripped; {@code null} when absent or empty. */
  String currency() {
    if (ccy == null) {
      return null;
    }
    String stripped = ccy.strip();
    return stripped.isEmpty() ? null : stripped;
  }

  /** Its first child named {@code name}, or {@code null}. */
  CamtNode child(String name) {
    for (CamtNode child : childNodes) {
      if (child.localName.equals(name)) {
        return child;
      }
    }
    return null;
  }

  /** Its children named {@code name}, in file order. */
  List<CamtNode> children(String name) {
    return childNodes.stream().filter(child -> child.localName.equals(name)).toList();
  }

  /** The descendant along {@code names}, each the first child of that name, or {@code null}. */
  CamtNode at(String... names) {
    CamtNode node = this;
    for (String name : names) {
      node = node.child(name);
      if (node == null) {
        return null;
      }
    }
    return node;
  }

  /** The text of the descendant along {@code names}, or {@code null}. */
  String valueAt(String... names) {
    CamtNode node = at(names);
    return node == null ? null : node.value();
  }
}
