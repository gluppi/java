/*
 * ATTENZIONE: quando si modifica questo programma in Eclipse, spuntare in
 * "Window->Preferences->Java->Code Style->Formatter->Edit->Off/On Eags->Enable off/on tags"
 * per evitare che facendo la formattazione automatica si scombinino
 * gli incolonnamenti nel sorgente.
 */

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.Part;
import javax.sql.DataSource;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUpload;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.RequestContext;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.servlet.ServletRequestContext;
import org.apache.commons.io.IOUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicNameValuePair;

import com.google.common.collect.Iterables;
import com.google.common.collect.ObjectArrays;

/**
 * This class is for recording http request in a database table, whose DDL is at the
 * end of this file. Use it as a filter in a tomcat webapp, including the following
 * lines in web.xml, adapting them to your needs.
 *  
    <filter>
      <filter-name>requestDumper</filter-name>
      <filter-class>FULLY.QUALIFIED.NAME.OF.DataSourceWrappedRequestDumpFilter</filter-class>
      <init-param>
       <description>Abilita o disabilita il dump delle richieste su DBMS</description>
       <param-name>isDumpActive</param-name>
       <param-value>true</param-value>
      </init-param>
      <init-param>
        <description>
           Nome del javax.sql.DataSource dove stanno le tabelle su cui devono essere
           ammucchiate le richieste, definito nel tag "resource-ref" del web.xml.
        </description>
        <param-name>dataSourceJndiName</param-name>
        <param-value>java:comp/env/jdbc/YOUR-RESOURCE-NAME</param-value>
      </init-param>
    </filter>
    <filter-mapping>
      <filter-name>requestDumper</filter-name>
      <url-pattern>/*</url-pattern>
    </filter-mapping>
 * 
 * @author gluppi 19 March 2015
 */
public class DataSourceWrappedRequestDumpFilter implements Filter
{
    /*
     * Safe copy/paste class reference to self.
     */
    private static final Class currentClass = new Object() { }.getClass().getEnclosingClass();

    /**
     * Nome dell'attibuto del ServletContext contenente lo stato di attivazione del filtro.
     */
    public static final String                        CTX_ENABLED_ATT_NAME = currentClass.getName() + ".enabled" ;
    
    /**
     * Nome del parametro, da utilizzare nella configurazione del filtro, che
     * indica se in fase di bootstrap della webapp il dump delle richieste deve
     * essere attivo.
     */
    public static final String                        INIT_PARAM_DUMP_ACTIVE         = "isDumpActive";

    /**
     * Nome del parametro, da utilizzare nella configurazione del filtro, che
     * indica come si chiama il DataSource contenente le tabelle su cui
     * ammucchiare le richieste. Il valore del parametro e` di tipo Stringa.
     * es.: "java:comp/env/jdbc/nomeDelDataSource"
     */
    public static final String                        INIT_PARAM_DATASOURCE_JNDINAME = "dataSourceJndiName";

    /**
     * Flag che indica se effettuare il dump oppure no. Quando false i dettagli
     * della request non vengono salvati sulle tabelle di dump.
     */
    private boolean                                   isDumpActive                   = true;

    /**
     * Valore di startup del parametro di configurazione INIT_PARAM_DUMP_ACTIVE.
     */
    private boolean initialIsDumpActive;

    /**
     * The logger for this class.
     */
    private Logger                                    logger                         = Logger.getLogger(getClass()
                                                                                             .getName());

    /**
     * Nome del DataSource.
     */
    private String                                    dataSourceName                 = null;

    /**
     * Il DataSource.
     */
    private DataSource                                dataSource                     = null;

    /**
     * Dimensione delle colonne della tabella di dump
     */
    private int queryStringColumnSize;
    private int requestUriColumnSize;
    private int servletPathColumnSize;
    private int idColumnSize;
    
    /**
     * 
     */
    @Override
    public void destroy()
    {
        // TODO: bisognerebbe togliere dal ServletContext l'attributo CTX_ENABLED_ATT_NAME, ma non vi abbiamo accesso.

        this.dataSource = null;
        this.dataSourceName = null;
    }

    /**
     * 
     */
    @Override
    public void doFilter(ServletRequest servletRequest,
            ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException
    {
        /*
         * Questo filtro può trattare solo richieste di tipo HttpServletRequest.
         * Se l'attuale richiesta non lo e`, proseguo con l'elaborazione dei filtri successivi.
         */
        if (!(servletRequest instanceof HttpServletRequest))
        {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        setActiveStatusUponContextAttribute(servletRequest);

        /*
         * Se il filtro non e` attivo, proseguiamo nella catena. 
         */
        if (!getIsDumpActive())
        {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;

        PersistedRequestWrapper persistedRequest = null;
        try
        {
            persistedRequest = new PersistedRequestWrapper(httpServletRequest, dataSource);
        }
        catch (SQLException ex)
        {
            logger.severe("Request not persisted!");
            throw new ServletException(ex);
        }

        chain.doFilter(persistedRequest, servletResponse);
    }

    /**
     * 
     */
    @Override
    public void init(FilterConfig config) throws ServletException
    {
        /*
         * Acquisizione dei parametri di inizializzazione di questo filtro.
         */

        {
            String active = config.getInitParameter(INIT_PARAM_DUMP_ACTIVE);
            setIsDumpActive((active == null) ? false : new Boolean(active));
            initialIsDumpActive = getIsDumpActive();
        }

        this.dataSourceName = config.getInitParameter(INIT_PARAM_DATASOURCE_JNDINAME);
        if (dataSourceName == null || dataSourceName.trim().length() == 0)
        {
            throw new ServletException(getClass().getName() + ": il parametro "
                    + INIT_PARAM_DATASOURCE_JNDINAME + " non e` valido.");
        }

        /*
         * Inserimento nel ServletContext dell'attributo per l'abilitazione/disabilitazione
         * del filtro.
         */
        {
            ServletContext context = config.getServletContext();
            context.setAttribute(CTX_ENABLED_ATT_NAME, getIsDumpActive());
        }

        /*
         * Acquisizione del DataSource.
         */
        try
        {
            Context initCtx = new InitialContext();
            this.dataSource = (DataSource) initCtx.lookup(dataSourceName);
        }
        catch (NamingException ex)
        {
            StringBuffer buffer = new StringBuffer();
            buffer.append("Error initializing datasource ");
            buffer.append(dataSourceName);
            logger.severe(buffer.toString());
            throw new ServletException(ex);
        }

        /*
         * Determinazione della lunghezza dei campi varchar passibili di troncamento. 
         */
        try
        {
            Connection conn = null;
            try
            {
                conn = dataSource.getConnection();
                this.idColumnSize = getColumnSize(conn, "HTTP_REQUESTS", "ID");
                this.queryStringColumnSize = getColumnSize(conn, "HTTP_REQUESTS", "QUERY_STRING");
                this.requestUriColumnSize = getColumnSize(conn, "HTTP_REQUESTS", "REQUEST_URI");
                this.servletPathColumnSize = getColumnSize(conn, "HTTP_REQUESTS", "SERVLET_PATH");
            }
            catch (SQLException ex)
            {
                throw new ServletException(ex);
            }
            finally
            {
                if (!conn.isClosed())
                {
                    conn.close();
                }
            }
        }
        catch (SQLException ex)
        {
            throw new ServletException(ex);
        }
    }

    /**
     * Imposta se fare il dump delle richieste o no.
     * 
     * @param isDumpActive
     */
    protected void setIsDumpActive(boolean isDumpActive)
    {
        this.isDumpActive = isDumpActive;
    }

    /**
     * @return True se il dump delle richieste è attivo, False se il dump è
     *         inattivo.
     */
    public Boolean getIsDumpActive()
    {
        return isDumpActive;
    }

    /**
     * Permette di attivare il dump delle richieste, eventualmente disattivato.
     */
    public void activate()
    {
        setIsDumpActive(true);
        logger.info("HTTP Request dump is now ACTIVE.");
    }

    /**
     * Permette di disattivare il dump delle richieste, senza dover intervenire
     * sulla configurazione della webapp.
     */
    public void deactivate()
    {
        setIsDumpActive(false);
        logger.info("HTTP Request dump is now INACTIVE.");
    }

    /**
     * Il filtro potrebbe essere stato disattivato impostanto a False l'attributo
     * del ServletContext CTX_ENABLED_ATT_NAME, quindi leggiamo tale attributo
     * per decidere poi cosa fare in base al suo valore.
     * Se l'attributo del ServletContext e` sparito o e` stato sovrascritto
     * con un attributo non Boolean, il filtro viene reimpostato nella condizione iniziale
     * definita nella configurazione della webapp.
     * 
     * @param request
     */
    private void setActiveStatusUponContextAttribute(ServletRequest request)
    {
        {
            ServletContext context = request.getServletContext();
            Object attribute = context.getAttribute(CTX_ENABLED_ATT_NAME);
            if (attribute != null && attribute instanceof Boolean)
            {
                boolean active = (Boolean) attribute;
                if (active)
                {
                    if (!getIsDumpActive())
                    {
                        activate();
                    }
                }
                else
                {
                    if (getIsDumpActive())
                    {
                        deactivate();
                    }
                }
            }
            else
            {
                if (initialIsDumpActive)
                {
                    if (!getIsDumpActive())
                    {
                        activate();
                    }
                }
                else
                {
                    if (getIsDumpActive())
                    {
                        deactivate();
                    }
                }
            }
        }
    }

    /**
     * @param conn
     * @return la dimensione della colonna "columnName" della tabella
     *         "tableName"
     * @throws SQLException
     */
    private int getColumnSize(Connection conn, String tableName, String columnName) throws SQLException
    {
        int size = 0;

        DatabaseMetaData dbMetaData = conn.getMetaData();
        ResultSet rs = dbMetaData.getColumns(null, null, tableName, columnName);
        size = rs.next() ? rs.getInt("COLUMN_SIZE") : 0;
        rs.close();

        return size;
    }

    /**
     * Rappresenta una ServletRequest salvata su db.
     */
    private final class PersistedRequestWrapper extends HttpServletRequestWrapper
    {
        private Integer               id;

        private DataSource            dataSource;

        private byte[]                buffer        = null;

        private Map<String, String[]> parametersMap = null;

        private Logger                logger        = Logger.getLogger(getClass().getName());

        /**
         * @param request
         * @param dataSource
         * @throws IOException
         * @throws SQLException
         */
        protected PersistedRequestWrapper(HttpServletRequest request,
                DataSource dataSource) throws IOException, SQLException
        {
            this(request);
            this.dataSource = dataSource;
            this.id = persist();
            this.parametersMap = getInternalParameterMap();
        }

        /**
         * Questa classe può essere istanziata solamente passando
         * al costruttore i necessari parametri.
         * @param req
         */
        private PersistedRequestWrapper(HttpServletRequest req)
        {
            super(req);
        }

        public String getIdAsString()
        {
            return (getId() == null) ? null : getId().toString();
        }

        public Integer getId()
        {
            return this.id;
        }

        /**
         * @return true solo se si tratta di una request di tipo multipart/form-data
         */
        public boolean getIsMultipartFormData()
        {
            boolean isMultipart = false;

            if (getContentType() != null)
            {
                String contentType = ContentType.parse(getContentType()).getMimeType();
                isMultipart = contentType.equals(ContentType.MULTIPART_FORM_DATA.getMimeType());
            }

            return isMultipart;
        }

        /**
         * @return true solo se si tratta di una request di tipo application/form-url-encoded
         */
        public boolean getIsApplicationFormUrlEncoded()
        {
            boolean isApplicationFormUrlEncoded = false;

            if (getContentType() != null)
            {
                String contentType = ContentType.parse(getContentType()).getMimeType();
                isApplicationFormUrlEncoded = contentType.equals(ContentType.APPLICATION_FORM_URLENCODED.getMimeType());
            }

            return isApplicationFormUrlEncoded;
        }

        /**
         * @return La stringa usata come deliminatore nel body per le richieste multipart.
         */
        public String getBoundary()
        {
            String boundary = null;

            if (getIsMultipartFormData())
            {
                String tokens[] = getContentType().toLowerCase().split(";");
                boundary = tokens[1].split("=")[1];
            }

            return boundary;
        }

        @Override
        public String getParameter(String name)
        {
            String[] params = this.parametersMap.get(name);
            return params != null && params.length > 0 ? params[0] : null;
        }

        @Override
        public String[] getParameterValues(String name)
        {
            return parametersMap.get(name);
        }

        @Override
        public Enumeration<String> getParameterNames()
        {
            return Collections.enumeration(parametersMap.keySet());
        }

        @Override
        public Map<String, String[]> getParameterMap()
        {
            return this.parametersMap;
        }

        /**
         * Legge dal DB il body della richiesta precedentemente salvata e
         * lo restituisce sotto forma di input stream.
         */
        @Override
        public ServletInputStream getInputStream() throws IOException
        {
            // @formatter:off
            String sql = 
               "SELECT body AS body " +
               "FROM   http_requests " +
               "WHERE  day = TO_CHAR(CURRENT_DATE, 'YYYYMMDD') " +
               "AND    id = ?";
            // @formatter:on

            Connection conn = null;
            PreparedStatement stat = null;
            ResultSet result = null;
            InputStream input = null;
            byte[] bytes = new byte[0];

            try
            {
                try
                {
                    conn = dataSource.getConnection();
                    stat = conn.prepareStatement(sql);
                    stat.setInt(1, getId());

                    result = stat.executeQuery();
                    if (result.next())
                    {
                        Clob content = result.getClob("body");
                        input = content.getAsciiStream();
                        bytes = IOUtils.toByteArray(input);
                    }

                    stat.close();
                    conn.close();
                    input.close();
                }
                catch (SQLException ex)
                {
                    StringBuffer buffer = new StringBuffer();
                    buffer.append("Error retrieving request. Values are: ");
                    buffer.append("id=" + id.toString() + ",");
                    logger.severe(buffer.toString());
                    throw ex;
                }
                finally
                {
                    if (stat != null && !stat.isClosed())
                        stat.close();
                    if (conn != null && !conn.isClosed())
                        conn.close();
                    if (input != null)
                        input.close();
                }
            }
            catch (SQLException ex)
            {
                throw new IOException(ex);
            }

            ByteArrayInputStream is = new ByteArrayInputStream(bytes);
            return new BufferedServletInputStream(is);
        }

        @Override
        public BufferedReader getReader() throws IOException
        {
            return new BufferedReader(new InputStreamReader(getInputStream()));
        }

        /**
         * @see javax.servlet.http.HttpServletRequestWrapper#getParts()
         */
        @Override
        public Collection<Part> getParts() throws IllegalStateException,
                IOException, ServletException
        {
            // TODO Auto-generated method stub: da adattare!
            Collection<Part> parts = super.getParts();
            for (Part part : parts)
            {
                String name = part.getName();
            }
            return parts;
        }

        private Map<String, String[]> getInternalParameterMap()
        {
            Iterable<NameValuePair> params = URLEncodedUtils.parse(
                    getQueryString(), Charset.forName("UTF8"));

            try
            {
                if (getIsApplicationFormUrlEncoded())
                {
                    List<NameValuePair> postParams = URLEncodedUtils.parse(
                            IOUtils.toString(getReader()),
                            Charset.forName("UTF8"));
                    params = Iterables.concat(params, postParams);
                }
                else if (getIsMultipartFormData())
                {
                    List<NameValuePair> parameterList = new ArrayList<NameValuePair>();

                    try
                    {
                        RequestContext context = new ServletRequestContext(this);
                        FileItemFactory factory = new DiskFileItemFactory();
                        FileUpload upload = new ServletFileUpload(factory);
                        List<FileItem> multipartItems = upload
                                .parseRequest(context);
                        parameterList = parseItems(multipartItems);
                    }
                    catch (FileUploadException ex)
                    {
                        throw new IOException(ex);
                    }

                    params = Iterables.concat(params, parameterList);
                }
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }

            Map<String, String[]> result = toMap(params);
            return result;
        }

        private List<NameValuePair> parseItems(List<FileItem> multipartItems)
        {
            List<NameValuePair> parameterList = new ArrayList<NameValuePair>();

            for (FileItem multipartItem : multipartItems)
            {
                String fieldName = multipartItem.getFieldName();
                String value = multipartItem.isFormField() ? multipartItem.getString() : getFileValue(multipartItem);
                if (value != null)
                {
                    NameValuePair pair = new BasicNameValuePair(fieldName, value);
                    parameterList.add(pair);
                }
            }

            return parameterList;
        }

        private String getFileValue(FileItem item)
        {
            StringBuffer buffer = null;

            if (item.getSize() > 0)
            {                
                String fileName = item.getName();
                String contentType = item.getContentType();
                String content = item.getString();
                Long size = item.getSize();

                buffer = new StringBuffer();
                buffer.append("{");
    
                buffer.append("fileName:");
                buffer.append(getQuotedString(fileName));
                buffer.append(",");
    
                buffer.append("contentType:");
                buffer.append(getQuotedString(contentType));
                buffer.append(",");
    
                buffer.append("size:");
                buffer.append(size.toString());
                buffer.append(",");
    
                buffer.append("content:");
                buffer.append(getQuotedString(content));
    
                buffer.append("}");
            }

            return (buffer == null) ? null : buffer.toString();
        }

        private Map<String, String[]> toMap(Iterable<NameValuePair> body)
        {
            Map<String, String[]> result = new LinkedHashMap<String, String[]>();
            for (NameValuePair e : body)
            {
                String key = e.getName();
                String value = e.getValue();
                if (result.containsKey(key))
                {
                    String[] newValue = ObjectArrays.concat(result.get(key), value);
                    result.remove(key);
                    result.put(key, newValue);
                }
                else
                {
                    result.put(key, new String[]
                    { value });
                }
            }
            return result;
        }

        /**
         * Salva la request, assegnendole una chiave primaria.
         * @return
         * @throws IOException
         * @throws SQLException
         */
        private Integer persist() throws IOException, SQLException
        {
            Integer id = null;
            Connection conn = null;

            try
            {
                int insertedRow = 0;
                conn = dataSource.getConnection();
                while (insertedRow == 0)
                {
                    id = getNextId(conn);
                    insertedRow = insertRow(conn, id);
                }
                conn.close();
            }
            catch (SQLException ex)
            {
                StringBuffer buffer = new StringBuffer();
                buffer.append("Error inserting request. Values are: ");
                buffer.append("id=" + id + ",");
                logger.severe(buffer.toString());
                throw ex;
            }
            finally
            {
                if (conn != null && !conn.isClosed())
                    conn.close();
            }

            return id;
        }

        /**
         * Inserisce il record della request.
         * @param conn
         * @param id
         * @return
         * @throws SQLException
         * @throws IOException
         */
        private int insertRow(Connection conn, int id) throws SQLException, IOException
        {
            // @formatter:off
            String sql =
                "INSERT INTO http_requests" +
                "(" +
                "   id,"                  + /*  1 */
                "   date_header,"         + /*  2 */
                "   method,"              + /*  3 */
                "   context_path,"        + /*  4 */
                "   path_info,"           + /*  5 */
                "   path_translated,"     + /*  6 */
                "   query_string,"        + /*  7 */
                "   request_uri,"         + /*  8 */
                "   servlet_path,"        + /*  9 */
                "   authentication_type," + /* 10 */
                "   remote_user,"         + /* 11 */
                "   sid,"                 + /* 12 */
                "   sid_is_from_cookie,"  + /* 13 */
                "   sid_is_from_url,"     + /* 14 */
                "   sid_is_valid,"        + /* 15 */
                "   content_type,"        + /* 16 */
                "   content_length,"      + /* 17 */
                "   character_encoding,"  + /* 18 */
                "   dispatcher_type,"     + /* 19 */
                "   locale,"              + /* 20 */
                "   local_address,"       + /* 21 */
                "   local_name,"          + /* 22 */
                "   local_port,"          + /* 23 */
                "   remote_address,"      + /* 24 */
                "   remote_host,"         + /* 25 */
                "   remote_port,"         + /* 26 */
                "   scheme_name,"         + /* 27 */
                "   server_name,"         + /* 28 */
                "   server_port,"         + /* 29 */
                "   protocol,"            + /* 30 */
                "   channel_is_secure,"   + /* 31 */
                "   async_is_supported,"  + /* 32 */
                "   async_is_started,"    + /* 33 */
                "   headers,"             + /* 34 */
                "   cookies,"             + /* 35 */
                "   body"                 + /* 36 */
                ")" +
                "VALUES" +
                "(" +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?," +
                "   ?" +
                ")" ;
            // @formatter:on

            int insertedRow = 0;

            PreparedStatement stat = null;
            OutputStream output = null;
            InputStream input = null;

            try
            {
                stat = conn.prepareStatement(sql);
    
                Clob headers = conn.createClob();
                headers.setString(1, getHeaders());

                Clob cookies = conn.createClob();
                cookies.setString(1, getCookiesString());

                Clob body = conn.createClob();
                output = body.setAsciiStream(1);
                input = super.getInputStream();
                IOUtils.copy(input, output);
                output.flush();

                // @formatter:off
                stat.setInt   ( 1, id) ;
                stat.setDate  ( 2, getDateHeaderAsDate());
                stat.setString( 3, getMethod()) ;
                stat.setString( 4, getContextPath()) ;
                stat.setString( 5, getPathInfo()) ;
                stat.setString( 6, getPathTranslated()) ;
                stat.setString( 7, trimToSize(getQueryString(), queryStringColumnSize)) ;
                stat.setString( 8, trimToSize(getRequestURI(), requestUriColumnSize)) ;
                stat.setString( 9, trimToSize(getServletPath(), servletPathColumnSize)) ;
                stat.setString(10, getAuthType()) ;
                stat.setString(11, getRemoteUser()) ;
                stat.setString(12, getRequestedSessionId()) ;
                stat.setInt   (13, boolToInt(isRequestedSessionIdFromCookie())) ;
                stat.setInt   (14, boolToInt(isRequestedSessionIdFromURL())) ;
                stat.setInt   (15, boolToInt(isRequestedSessionIdValid())) ;
                stat.setString(16, getContentType()) ;
                if (getContentLength() != -1)
                {
                    stat.setInt   (17, getContentLength()) ;
                }
                else
                {
                    stat.setNull(17, Types.NUMERIC) ;
                }
                stat.setString(18, getCharacterEncoding()) ;
                stat.setString(19, getDispatcherType().name()) ;
                stat.setString(20, getLocale().getDisplayName()) ;
                stat.setString(21, getLocalAddr()) ;
                stat.setString(22, getLocalName()) ;
                stat.setInt   (23, getLocalPort()) ;
                stat.setString(24, getRemoteAddr()) ;
                stat.setString(25, getRemoteHost()) ;
                stat.setInt   (26, getRemotePort()) ;
                stat.setString(27, getScheme()) ;
                stat.setString(28, getServerName()) ;
                stat.setInt   (29, getServerPort()) ;
                stat.setString(30, getProtocol()) ;
                stat.setInt   (31, boolToInt(isSecure())) ;
                stat.setInt   (32, boolToInt(isAsyncSupported())) ;
                stat.setInt   (33, boolToInt(isAsyncStarted())) ;
                stat.setClob  (34, headers);
                stat.setClob  (35, cookies);
                stat.setClob  (36, body);
                // @formatter:on
    
                insertedRow = stat.executeUpdate();
                stat.close();
                output.close();
                input.close();
            }
            catch (SQLIntegrityConstraintViolationException ex)
            {
                /*
                 * Dal momento che la tabella non ha né chiavi esterne né
                 * vincoli UNIQUE, la violazione di integrità in questo punto
                 * può essere solamente una chiave primaria doppia.
                 * Ignoriamo l'eccezione perchè ritentiamo l'inserimento
                 * con un'altra chiave primaria.
                 * Potrebbe succedere che il programma entri in un ciclo
                 * infinito se l'id assegnato al record raggiunge il valore massimo
                 * inseribile nel campo. In tal caso rilanciamo l'eccezione.
                 */

                String maxValue = String.format("%0" + idColumnSize + "d", 0).replace("0", "9");

                if (id == Integer.parseInt(maxValue))
                {
                    throw new SQLIntegrityConstraintViolationException("id max value reached! You need to increase the size of the id column in table http_requests and restart the webapp.");
                }
            }
            finally
            {
                if (stat != null && !stat.isClosed())
                    stat.close();
                if (output != null)
                    output.close();
                if (input != null)
                    input.close();
            }

            return insertedRow;
        }

        /**
         * @param conn
         * @return Il prossimo id da utilizzare per l'inserimento della request.
         * @throws Exception
         */
        private synchronized int getNextId(Connection conn) throws SQLException
        {
            // @formatter:off
            String sql =
               "SELECT    MAX(id)+1 AS nextid " +
               "FROM      http_requests " +
               "WHERE     day = TO_CHAR(CURRENT_DATE, 'YYYYMMDD')" ;
            // @formatter:on

            int nextId = 0;

            Statement stat = null;
            ResultSet result = null;
            try
            {
                stat = conn.createStatement();
                result = stat.executeQuery(sql);
                if (result.next())
                {
                    nextId = (result.getInt("nextid") == 0) ? 1 : result
                            .getInt("nextid");
                }
                result.close();
                stat.close();
            }
            catch (SQLException ex)
            {
                logger.severe("Cannot get next id!");
                throw ex;
            }
            finally
            {
                if (result != null && !result.isClosed())
                    result.close();
                if (stat != null && !stat.isClosed())
                    stat.close();
            }

            return nextId;
        }

        /**
         * @return In un'unica stringa tutti gli headers della request, separati da newlines.
         */
        private String getHeaders()
        {
            StringBuffer buffer = new StringBuffer(); 

            Enumeration<String> headerNames = getHeaderNames();
            while (headerNames!= null && headerNames.hasMoreElements())
            {
                String headerName = headerNames.nextElement() ;
                String headerValue = getHeader(headerName) ;
                Enumeration<String> headerValues = getHeaders(headerName);
                while(headerValues != null && headerValues.hasMoreElements())
                {
                    String value = headerValues.nextElement();
                    buffer.append(headerName) ;
                    buffer.append(": ") ;
                    buffer.append(value) ;
                    buffer.append("\n");
                }
            }

            return (buffer.length() == 0) ? null : buffer.toString() ;
        }

        /**
         * @return
         */
        private String getCookiesString()
        {
            StringBuffer buffer = new StringBuffer();

            Cookie cookies[] = getCookies();
            for (int nr = 0; nr < cookies.length; nr++)
            {
                Cookie cookie = cookies[nr];
                buffer.append("{");

                buffer.append("name:");
                buffer.append(getQuotedString(cookie.getName()));
                buffer.append(",");

                buffer.append("value:");
                buffer.append(getQuotedString(cookie.getValue()));
                buffer.append(",");

                buffer.append("maxAge:");
                buffer.append(cookie.getMaxAge());
                buffer.append(",");

                buffer.append("comment:");
                buffer.append(getQuotedString(cookie.getComment()));
                buffer.append(",");

                buffer.append("domain:");
                buffer.append(getQuotedString(cookie.getDomain()));
                buffer.append(",");

                buffer.append("path:");
                buffer.append(getQuotedString(cookie.getPath()));
                buffer.append(",");

                buffer.append("secure:");
                buffer.append(cookie.getSecure());
                buffer.append(",");

                buffer.append("version:");
                buffer.append(cookie.getVersion());
                buffer.append(",");

                buffer.append("isHttpOnly:");
                buffer.append(cookie.isHttpOnly());

                buffer.append("}");
                buffer.append("\n");
            }

            return (buffer.length() == 0) ? null : buffer.toString();
        }

        /**
         * @param value
         * @return La stringa indicata racchiusa tra doppi apici, se non null, altrimenti null.
         */
        private String getQuotedString(String value)
        {
            return (value == null) ? null : ("\"" + value + "\"") ;
        }

        /**
         */
        public Date getDateHeaderAsDate()
        {
            Date date = null;

            try
            {
                Long dateLong = getDateHeader("Date") ;
                date = (dateLong.intValue() == -1) ? null : new Date(dateLong);    
            }
            catch (IllegalArgumentException ignored)
            {
            }
            
            return date ;
        }

        /**
         * @param bool
         * @return
         */
        private int boolToInt(boolean bool)
        {
            return bool ? 1 : 0;
        }

        /**
         * @param string
         * @param size
         * @return La stringa specificata accorciata alla dimensione indicata, se
         *         piu` lunga, altrimenti la stringa stessa.
         */
        private String trimToSize(String string, int size)
        {
            if (string == null)
            {
                return null;
            }

            return string.length() <= size ? string : string.substring(0, size);
        }
    }

    private static final class BufferedServletInputStream extends
            ServletInputStream
    {
        private ByteArrayInputStream input;

        public BufferedServletInputStream(ByteArrayInputStream input)
        {
            this.input = input;
        }

        @Override
        public int available()
        {
            return this.input.available();
        }

        @Override
        public int read()
        {
            return this.input.read();
        }

        @Override
        public int read(byte[] buf, int off, int len)
        {
            return this.input.read(buf, off, len);
        }
    }
}
// @formatter:off
/*
CREATE TABLE http_requests
(
    day                             NUMBER(8)         DEFAULT TO_CHAR(CURRENT_DATE, 'YYYYMMDD')
                                                      NOT NULL,
    id                              NUMBER(6)         NOT NULL,
    date_header                     DATE,
    method                          VARCHAR(7),
    context_path                    VARCHAR(255),
    path_info                       VARCHAR(255),
    path_translated                 VARCHAR(255),
    query_string                    VARCHAR(1024),
    request_uri                     VARCHAR(255),
    servlet_path                    VARCHAR(255),
    authentication_type             VARCHAR(11),
    remote_user                     VARCHAR(255),
    sid                             VARCHAR(127),
    sid_is_from_cookie              NUMBER(1)         DEFAULT 0 NOT NULL,
    sid_is_from_url                 NUMBER(1)         DEFAULT 0 NOT NULL,
    sid_is_valid                    NUMBER(1)         DEFAULT 0 NOT NULL,
    content_type                    VARCHAR(72),
    content_length                  NUMBER(9),
    character_encoding              VARCHAR(64),
    dispatcher_type                 VARCHAR(7),
    locale                          VARCHAR(64),
    local_address                   CHAR(15),
    local_name                      VARCHAR(64),
    local_port                      NUMBER(5),
    remote_address                  CHAR(15),
    remote_host                     VARCHAR(64),
    remote_port                     NUMBER(5),
    scheme_name                     VARCHAR(8),
    server_name                     VARCHAR(64),
    server_port                     NUMBER(5),
    protocol                        VARCHAR(32),
    channel_is_secure               NUMBER(1)         DEFAULT 0 NOT NULL,
    async_is_supported              NUMBER(1)         DEFAULT 0 NOT NULL,
    async_is_started                NUMBER(1)         DEFAULT 0 NOT NULL,
    headers                         CLOB,
    cookies                         CLOB,
    body                            CLOB,
    ins                             TIMESTAMP         DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT Krequests01          CHECK (id >= 0),
    CONSTRAINT Prequests            PRIMARY KEY (day, id)
)   
LOB(body) STORE AS (DISABLE STORAGE IN ROW PCTVERSION 0 NOCACHE NOLOGGING)
;

-- DROP TABLE http_requests ;
*/
// @formatter:on
