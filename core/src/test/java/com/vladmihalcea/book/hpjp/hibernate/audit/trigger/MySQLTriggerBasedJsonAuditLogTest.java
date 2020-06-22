package com.vladmihalcea.book.hpjp.hibernate.audit.trigger;

import com.vladmihalcea.book.hpjp.util.AbstractTest;
import com.vladmihalcea.book.hpjp.util.providers.Database;
import com.vladmihalcea.hibernate.type.util.ReflectionUtils;
import org.hibernate.Session;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.Test;

import javax.persistence.*;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * @author Vlad Mihalcea
 */
public class MySQLTriggerBasedJsonAuditLogTest extends AbstractTest {

    @Override
    protected Class<?>[] entities() {
        return new Class[]{
            Book.class
        };
    }

    @Override
    protected Database database() {
        return Database.MYSQL;
    }

    @Override
    protected void afterInit() {
        ddl("DROP TABLE IF EXISTS book_audit_log");
        ddl("""
            CREATE TABLE IF NOT EXISTS book_audit_log (
                id BIGINT, 
            	old_row_data JSON,
            	new_row_data JSON,
            	dml_type ENUM('INSERT', 'UPDATE', 'DELETE') NOT NULL,
            	dml_timestamp TIMESTAMP NOT NULL,
            	dml_created_by VARCHAR(255) NOT NULL,
            	PRIMARY KEY (id, dml_type, dml_timestamp)
            ) 
            """
        );

        ddl("""          
            CREATE TRIGGER book_insert_audit_trigger
            AFTER INSERT ON book
            FOR EACH ROW BEGIN
            INSERT INTO book_audit_log (
                id,
                old_row_data,
                new_row_data,
                dml_type,
                dml_timestamp,
                dml_created_by
            )
            VALUES(
                NEW.id,
                null,
                JSON_OBJECT(
                    "title", NEW.title,
                    "author", NEW.author,
                    "price_in_cents", NEW.price_in_cents,
                    "publisher", NEW.publisher
                ),
                'INSERT',
                CURRENT_TIMESTAMP,
                @logged_user
            );
            END
            """
        );

        ddl("""
            CREATE TRIGGER book_update_audit_trigger
            AFTER UPDATE ON book
            FOR EACH ROW BEGIN
            INSERT INTO book_audit_log (
                id,
                old_row_data,
                new_row_data,
                dml_type,
                dml_timestamp,
                dml_created_by
            )
            VALUES(
                NEW.id,
                JSON_OBJECT(
                    "title", OLD.title,
                    "author", OLD.author,
                    "price_in_cents", OLD.price_in_cents,
                    "publisher", OLD.publisher
                ),
                JSON_OBJECT(
                    "title", NEW.title,
                    "author", NEW.author,
                    "price_in_cents", NEW.price_in_cents,
                    "publisher", NEW.publisher
                ),
                'UPDATE',
                CURRENT_TIMESTAMP,
                @logged_user
            );
            END
            """
        );

        ddl("""
            CREATE TRIGGER book_delete_audit_trigger
            AFTER DELETE ON book
            FOR EACH ROW BEGIN
            INSERT INTO book_audit_log (
                id,
                old_row_data,
                new_row_data,
                dml_type,
                dml_timestamp,
                dml_created_by
            )
            VALUES(
                OLD.id,
                JSON_OBJECT(
                    "title", OLD.title,
                    "author", OLD.author,
                    "price_in_cents", OLD.price_in_cents,
                    "publisher", OLD.publisher
                ),
                null,
                'DELETE',
                CURRENT_TIMESTAMP,
                @logged_user
            );
            END
            """
        );
    }

    @Test
    public void test() {
        LoggedUser.logIn("Vlad Mihalcea");

        doInJPA(entityManager -> {
            setCurrentLoggedUser(entityManager);

            entityManager.persist(
                new Book()
                    .setId(1L)
                    .setTitle("High-Performance Java Persistence 1st edition")
                    .setAuthor("Vlad Mihalcea")
            );
        });

        doInJPA(entityManager -> {
            List<Tuple> revisions = getPostRevisions(entityManager);

            assertEquals(1, revisions.size());
        });

        doInJPA(entityManager -> {
            setCurrentLoggedUser(entityManager);

            Book book = entityManager.find(Book.class, 1L)
                .setPublisher("Amazon")
                .setPriceInCents(4499);
        });

        doInJPA(entityManager -> {
            List<Tuple> revisions = getPostRevisions(entityManager);

            assertEquals(2, revisions.size());
        });

        doInJPA(entityManager -> {
            setCurrentLoggedUser(entityManager);

            entityManager.remove(
                entityManager.getReference(Book.class, 1L)
            );
        });

        doInJPA(entityManager -> {
            List<Tuple> revisions = getPostRevisions(entityManager);

            assertEquals(3, revisions.size());
        });
    }

    private void setCurrentLoggedUser(EntityManager entityManager) {
        Session session = entityManager.unwrap(Session.class);
        Dialect dialect = session.getSessionFactory().unwrap(SessionFactoryImplementor.class).getJdbcServices().getDialect();
        String loggedUser = ReflectionUtils.invokeMethod(
            dialect,
            "escapeLiteral",
            LoggedUser.get()
        );

        session.doWork(connection -> {
            update(
                connection,
                String.format(
                    "SET @logged_user = '%s'", loggedUser
                )
            );
        });
    }

    private List<Tuple> getPostRevisions(EntityManager entityManager) {
        return entityManager.createNativeQuery("""
            SELECT *
            FROM book_audit_log 
            """, Tuple.class)
        .getResultList();
    }

    public static class LoggedUser {

        private static final ThreadLocal<String> userHolder = new ThreadLocal<>();

        public static void logIn(String user) {
            userHolder.set(user);
        }

        public static void logOut() {
            userHolder.remove();
        }

        public static String get() {
            return userHolder.get();
        }
    }

    @Entity(name = "Book")
    @Table(name = "book")
    public static class Book {

        @Id
        private Long id;

        private String title;

        private String author;

        @Column(name = "price_in_cents")
        private int priceInCents;

        private String publisher;

        public Long getId() {
            return id;
        }

        public Book setId(Long id) {
            this.id = id;
            return this;
        }

        public String getTitle() {
            return title;
        }

        public Book setTitle(String title) {
            this.title = title;
            return this;
        }

        public String getAuthor() {
            return author;
        }

        public Book setAuthor(String author) {
            this.author = author;
            return this;
        }

        public int getPriceInCents() {
            return priceInCents;
        }

        public Book setPriceInCents(int priceInCents) {
            this.priceInCents = priceInCents;
            return this;
        }

        public String getPublisher() {
            return publisher;
        }

        public Book setPublisher(String publisher) {
            this.publisher = publisher;
            return this;
        }
    }
}
