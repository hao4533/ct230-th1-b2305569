package vn.edu.bookstore.legacy.adapter.soap;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;

import jakarta.xml.soap.SOAPFault;
import jakarta.xml.ws.WebServiceException;
import jakarta.xml.ws.soap.SOAPFaultException;

import org.springframework.stereotype.Component;

import vn.edu.bookstore.legacy.adapter.soap.LegacyCatalogException.Kind;
import vn.edu.bookstore.legacy.adapter.soap.generated.*;

@Component
public class LegacyCatalogClient {

    private final BookCatalogPortType port;

    public LegacyCatalogClient(BookCatalogPortType port) {
        this.port = port;
    }

    public LegacyBook getBook(String isbn) {
        GetBookRequest request = new GetBookRequest();
        request.setIsbn(isbn);
        try {
            return toModel(port.getBook(request).getBook());
        } catch (BookCatalogFault fault) {
            throw translate(fault);
        } catch (SOAPFaultException e) {
            throw translate(e);
        } catch (WebServiceException e) {
            throw unavailable(e);
        }
    }

    public List<LegacyBook> search(String keyword, int maxResults) {
        SearchBooksRequest request = new SearchBooksRequest();
        request.setKeyword(keyword);
        request.setMaxResults(maxResults);
        try {
            return port.searchBooks(request).getBook().stream()
                    .map(LegacyCatalogClient::toModel)
                    .toList();
        } catch (BookCatalogFault fault) {
            throw translate(fault);
        } catch (SOAPFaultException e) {
            throw translate(e);
        } catch (WebServiceException e) {
            throw unavailable(e);
        }
    }

    static LegacyCatalogException translate(BookCatalogFault fault) {
        ErrorDetail d = fault.getFaultInfo();
        String code = d == null ? "UNKNOWN" : d.getCode();
        String message = (d == null || d.getMessage() == null) ? fault.getMessage() : d.getMessage();
        Kind kind = switch (code) {
            case "BOOK_NOT_FOUND" -> Kind.NOT_FOUND;
            case "INVALID_ISBN", "INVALID_REQUEST" -> Kind.INVALID_REQUEST;
            default -> Kind.UPSTREAM_ERROR;
        };
        return new LegacyCatalogException(kind, code, message, fault);
    }

    static LegacyCatalogException translate(SOAPFaultException e) {
        SOAPFault f = e.getFault();
        String faultCode = f.getFaultCodeAsQName().getLocalPart();
        Kind kind = "Client".equals(faultCode) ? Kind.INVALID_REQUEST : Kind.UPSTREAM_ERROR;
        return new LegacyCatalogException(kind, "soap:" + faultCode, f.getFaultString(), e);
    }

    static LegacyCatalogException unavailable(WebServiceException e) {
        boolean timeout = hasCause(e, SocketTimeoutException.class) || hasCause(e, HttpTimeoutException.class);
        return new LegacyCatalogException(
                timeout ? Kind.UPSTREAM_TIMEOUT : Kind.UPSTREAM_UNAVAILABLE,
                null,
                "Không gọi được BookCatalogService: " + e.getMessage(),
                e
        );
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static LegacyBook toModel(Book b) {
        if (b == null) return null;
        return new LegacyBook(
                b.getIsbn(),
                b.getTitle(),
                b.getAuthor(),
                b.getAuthorId() != null ? b.getAuthorId().intValue() : null,
                b.getPublishedYear() != null ? b.getPublishedYear().getYear() : null,
                b.getPrice() != null ? b.getPrice().getAmount() : null,
                b.getPrice() != null ? b.getPrice().getCurrency().value() : null
        );
    }
}