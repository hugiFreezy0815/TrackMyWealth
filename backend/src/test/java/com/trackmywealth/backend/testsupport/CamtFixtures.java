package com.trackmywealth.backend.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * #350: synthetic ISO 20022 CAMT statements for tests. Every value is invented; the IBANs are the
 * published example IBANs of ISO 13616. Each message renders the differences of its version: the
 * entry status ({@code Sts} up to {@code .001.04}, {@code Sts/Cd} from {@code .001.06}), a detail's
 * amount ({@code AmtDtls/TxAmt} in {@code .001.02}, {@code TxDtls/Amt} and its own direction from
 * {@code .001.04}) and party names ({@code Pty/Nm} from {@code .001.08}).
 */
public final class CamtFixtures {

  public static final String IBAN = "DE89 3704 0044 0532 0130 00";
  public static final String IBAN_NORMALIZED = "DE89370400440532013000";
  public static final String OTHER_IBAN = "GB82 WEST 1234 5698 7654 32";
  public static final String STATEMENT = "camt.053";
  public static final String REPORT = "camt.052";

  private CamtFixtures() {}

  public static Message camt053(String version) {
    return new Message(STATEMENT, version);
  }

  public static Message camt052(String version) {
    return new Message(REPORT, version);
  }

  /** A booked entry; status, references and details are added on it. */
  public static Entry entry(String amount, String creditDebit, String bookingDate) {
    return new Entry(amount, creditDebit, bookingDate);
  }

  public static Detail detail(String amount, String creditDebit) {
    return new Detail(amount, creditDebit);
  }

  public static String namespace(String message, String version) {
    return "urn:iso:std:iso:20022:tech:xsd:" + message + "." + version;
  }

  /** A ZIP archive of {@code files}, named {@code statement-<n>.xml}. */
  public static byte[] zip(byte[]... files) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (int i = 0; i < files.length; i++) {
        zip.putNextEntry(new ZipEntry("statement-" + (i + 1) + ".xml"));
        zip.write(files[i]);
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String element(String name, String text) {
    return text == null ? "" : "<" + name + ">" + escape(text) + "</" + name + ">";
  }

  private static String amount(String name, String value, String currency) {
    return "<" + name + " Ccy=\"" + currency + "\">" + value + "</" + name + ">";
  }

  private static boolean fromVersion(String version, String first) {
    return version.compareTo(first) >= 0;
  }

  /** One message: statements (or reports) of accounts, rendered for its version. */
  public static final class Message {

    private final String message;
    private final String version;
    private final List<Statement> statements = new ArrayList<>();
    private String extra = "";

    private Message(String message, String version) {
      this.message = message;
      this.version = version;
    }

    public Message statement(String iban, Consumer<Statement> content) {
      Statement statement = new Statement(iban, null);
      content.accept(statement);
      statements.add(statement);
      return this;
    }

    /** A statement of an account identified without an IBAN ({@code Othr/Id}). */
    public Message statementOfOtherAccount(String accountId, Consumer<Statement> content) {
      Statement statement = new Statement(null, accountId);
      content.accept(statement);
      statements.add(statement);
      return this;
    }

    /** Raw XML placed in the message element, after its group header. */
    public Message raw(String xml) {
      extra = xml;
      return this;
    }

    public String xml() {
      String container = STATEMENT.equals(message) ? "BkToCstmrStmt" : "BkToCstmrAcctRpt";
      String statementElement = STATEMENT.equals(message) ? "Stmt" : "Rpt";
      StringBuilder xml =
          new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
              .append("<Document xmlns=\"")
              .append(namespace(message, version))
              .append("\"><")
              .append(container)
              .append(
                  "><GrpHdr><MsgId>MSG-1</MsgId><CreDtTm>2031-02-01T06:00:00</CreDtTm></GrpHdr>")
              .append(extra);
      for (Statement statement : statements) {
        xml.append('<').append(statementElement).append("><Id>S-1</Id>");
        xml.append(statement.xml(version));
        xml.append("</").append(statementElement).append('>');
      }
      return xml.append("</").append(container).append("></Document>").toString();
    }

    public byte[] bytes() {
      return xml().getBytes(StandardCharsets.UTF_8);
    }
  }

  /** One statement's account, balances and entries. */
  public static final class Statement {

    private final String iban;
    private final String otherId;
    private final List<String> balances = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();

    private Statement(String iban, String otherId) {
      this.iban = iban;
      this.otherId = otherId;
    }

    public Statement balance(String type, String amount, String creditDebit, String date) {
      return balance(type, amount, "EUR", creditDebit, "<Dt>" + date + "</Dt>");
    }

    public Statement balanceAt(String type, String amount, String creditDebit, String dateTime) {
      return balance(type, amount, "EUR", creditDebit, "<DtTm>" + dateTime + "</DtTm>");
    }

    public Statement balance(
        String type, String amount, String currency, String creditDebit, String dateXml) {
      balances.add(
          "<Bal><Tp><CdOrPrtry><Cd>"
              + type
              + "</Cd></CdOrPrtry></Tp>"
              + amount("Amt", amount, currency)
              + "<CdtDbtInd>"
              + creditDebit
              + "</CdtDbtInd><Dt>"
              + dateXml
              + "</Dt></Bal>");
      return this;
    }

    public Statement entry(Entry entry) {
      entries.add(entry);
      return this;
    }

    private String xml(String version) {
      StringBuilder xml = new StringBuilder("<Acct><Id>");
      xml.append(
          iban != null ? element("IBAN", iban) : "<Othr>" + element("Id", otherId) + "</Othr>");
      xml.append("</Id><Ccy>EUR</Ccy></Acct>");
      balances.forEach(xml::append);
      entries.forEach(entry -> xml.append(entry.xml(version)));
      return xml.toString();
    }
  }

  /** One {@code Ntry}. */
  public static final class Entry {

    private final String amount;
    private final String creditDebit;
    private final String bookingDate;
    private String currency = "EUR";
    private String status = "BOOK";
    private String bookingXml;
    private String valueDate;
    private String reference;
    private String information;
    private String reversal;
    private String bankCode = "";
    private String declared;
    private final List<Detail> details = new ArrayList<>();

    private Entry(String amount, String creditDebit, String bookingDate) {
      this.amount = amount;
      this.creditDebit = creditDebit;
      this.bookingDate = bookingDate;
    }

    public Entry currency(String code) {
      currency = code;
      return this;
    }

    public Entry status(String code) {
      status = code;
      return this;
    }

    /** The booking date as a date-time ({@code BookgDt/DtTm}). */
    public Entry bookedAt(String dateTime) {
      bookingXml = "<DtTm>" + dateTime + "</DtTm>";
      return this;
    }

    public Entry valueDate(String date) {
      valueDate = date;
      return this;
    }

    public Entry reference(String accountServicerReference) {
      reference = accountServicerReference;
      return this;
    }

    public Entry information(String text) {
      information = text;
      return this;
    }

    public Entry reversal(String indicator) {
      reversal = indicator;
      return this;
    }

    public Entry bankCode(String domain, String family, String subFamily) {
      bankCode =
          "<BkTxCd><Domn><Cd>"
              + domain
              + "</Cd><Fmly><Cd>"
              + family
              + "</Cd><SubFmlyCd>"
              + subFamily
              + "</SubFmlyCd></Fmly></Domn></BkTxCd>";
      return this;
    }

    public Entry proprietaryCode(String code, String issuer) {
      bankCode =
          "<BkTxCd><Prtry>" + element("Cd", code) + element("Issr", issuer) + "</Prtry></BkTxCd>";
      return this;
    }

    /** {@code NtryDtls/Btch/NbOfTxs}: how many transactions the entry says it holds. */
    public Entry declared(String count) {
      declared = count;
      return this;
    }

    public Entry detail(Detail detail) {
      details.add(detail);
      return this;
    }

    private String xml(String version) {
      StringBuilder xml = new StringBuilder("<Ntry>");
      xml.append(element("NtryRef", "E-1"));
      xml.append(amount("Amt", amount, currency));
      xml.append(element("CdtDbtInd", creditDebit));
      xml.append(element("RvslInd", reversal));
      if (status != null) {
        xml.append(
            fromVersion(version, "001.06")
                ? "<Sts>" + element("Cd", status) + "</Sts>"
                : element("Sts", status));
      }
      xml.append("<BookgDt>")
          .append(bookingXml != null ? bookingXml : element("Dt", bookingDate))
          .append("</BookgDt>");
      if (valueDate != null) {
        xml.append("<ValDt>").append(element("Dt", valueDate)).append("</ValDt>");
      }
      xml.append(element("AcctSvcrRef", reference));
      xml.append(bankCode);
      if (!details.isEmpty() || declared != null) {
        xml.append("<NtryDtls>");
        if (declared != null) {
          xml.append("<Btch>").append(element("NbOfTxs", declared)).append("</Btch>");
        }
        details.forEach(detail -> xml.append(detail.xml(version)));
        xml.append("</NtryDtls>");
      }
      xml.append(element("AddtlNtryInf", information));
      return xml.append("</Ntry>").toString();
    }
  }

  /** One {@code TxDtls}. */
  public static final class Detail {

    private final String amount;
    private final String creditDebit;
    private String currency = "EUR";
    private String reference;
    private String endToEndId;
    private String creditor;
    private String debtor;
    private final List<String> remittance = new ArrayList<>();
    private String instructedAmount;
    private String instructedCurrency;
    private String rate;
    private String bankCode = "";

    private Detail(String amount, String creditDebit) {
      this.amount = amount;
      this.creditDebit = creditDebit;
    }

    public Detail currency(String code) {
      currency = code;
      return this;
    }

    public Detail reference(String accountServicerReference) {
      reference = accountServicerReference;
      return this;
    }

    public Detail endToEndId(String id) {
      endToEndId = id;
      return this;
    }

    public Detail creditor(String name) {
      creditor = name;
      return this;
    }

    public Detail debtor(String name) {
      debtor = name;
      return this;
    }

    public Detail remittance(String text) {
      remittance.add(text);
      return this;
    }

    public Detail instructed(String instructed, String instructedIn, String exchangeRate) {
      instructedAmount = instructed;
      instructedCurrency = instructedIn;
      rate = exchangeRate;
      return this;
    }

    public Detail proprietaryCode(String code) {
      bankCode = "<BkTxCd><Prtry>" + element("Cd", code) + "</Prtry></BkTxCd>";
      return this;
    }

    private String xml(String version) {
      boolean ownAmount = fromVersion(version, "001.04");
      StringBuilder xml = new StringBuilder("<TxDtls>");
      if (reference != null || endToEndId != null) {
        xml.append("<Refs>")
            .append(element("AcctSvcrRef", reference))
            .append(element("EndToEndId", endToEndId))
            .append("</Refs>");
      }
      if (ownAmount && amount != null) {
        xml.append(amount("Amt", amount, currency));
        xml.append(element("CdtDbtInd", creditDebit));
      }
      if (instructedAmount != null || !ownAmount && amount != null) {
        xml.append("<AmtDtls>");
        if (instructedAmount != null) {
          xml.append("<InstdAmt>").append(amount("Amt", instructedAmount, instructedCurrency));
          if (rate != null) {
            xml.append("<CcyXchg><SrcCcy>")
                .append(instructedCurrency)
                .append("</SrcCcy><TrgtCcy>")
                .append(currency)
                .append("</TrgtCcy>")
                .append(element("XchgRate", rate))
                .append("</CcyXchg>");
          }
          xml.append("</InstdAmt>");
        }
        if (!ownAmount && amount != null) {
          xml.append("<TxAmt>").append(amount("Amt", amount, currency)).append("</TxAmt>");
        }
        xml.append("</AmtDtls>");
      }
      xml.append(bankCode);
      if (creditor != null || debtor != null) {
        xml.append("<RltdPties>");
        xml.append(party("Dbtr", debtor, version));
        xml.append(party("Cdtr", creditor, version));
        xml.append("</RltdPties>");
      }
      if (!remittance.isEmpty()) {
        xml.append("<RmtInf>");
        remittance.forEach(text -> xml.append(element("Ustrd", text)));
        xml.append("</RmtInf>");
      }
      return xml.append("</TxDtls>").toString();
    }

    private static String party(String role, String name, String version) {
      if (name == null) {
        return "";
      }
      String named = element("Nm", name);
      return "<"
          + role
          + ">"
          + (fromVersion(version, "001.08") ? "<Pty>" + named + "</Pty>" : named)
          + "</"
          + role
          + ">";
    }
  }
}
