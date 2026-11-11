package cl.duoc.barriodigital.requests;

import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.service.RequestListQuery;

import java.time.LocalDate;

/** Arma un RequestListQuery solo con los parámetros que usa cada test; los demás quedan null. */
public final class TestListQueries {

    private RequestStatus status;
    private LocalDate from;
    private LocalDate to;
    private String assignee;
    private String open;
    private String q;
    private String procedureType;
    private String sector;
    private Integer page;
    private Integer size;
    private String sort;

    private TestListQueries() {
    }

    public static TestListQueries query() {
        return new TestListQueries();
    }

    /** Sin filtros, primera página de 20 y orden por defecto. */
    public static RequestListQuery all() {
        return query().build();
    }

    public TestListQueries status(RequestStatus value) { status = value; return this; }
    public TestListQueries from(LocalDate value) { from = value; return this; }
    public TestListQueries to(LocalDate value) { to = value; return this; }
    public TestListQueries assignee(String value) { assignee = value; return this; }
    public TestListQueries open(String value) { open = value; return this; }
    public TestListQueries q(String value) { q = value; return this; }
    public TestListQueries procedureType(String value) { procedureType = value; return this; }
    public TestListQueries sector(String value) { sector = value; return this; }
    public TestListQueries page(Integer value) { page = value; return this; }
    public TestListQueries size(Integer value) { size = value; return this; }
    public TestListQueries sort(String value) { sort = value; return this; }

    public RequestListQuery build() {
        return new RequestListQuery(status, from, to, assignee, open, q, procedureType, sector, page, size, sort);
    }
}
