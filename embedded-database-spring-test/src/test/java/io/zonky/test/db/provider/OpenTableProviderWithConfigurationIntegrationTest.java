/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.zonky.test.db.provider;

import com.opentable.db.postgres.embedded.EmbeddedPostgres;
import io.zonky.test.category.PostgresTestSuite;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.support.TestSocketUtils;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.function.Consumer;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.OPENTABLE;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
@Category(PostgresTestSuite.class)
@AutoConfigureEmbeddedDatabase(type = POSTGRES, provider = OPENTABLE)
@ContextConfiguration
public class OpenTableProviderWithConfigurationIntegrationTest {

    @Configuration
    static class Config {

        @Bean
        public Integer randomPort() {
            return TestSocketUtils.findAvailableTcpPort();
        }

        @Bean
        public Consumer<EmbeddedPostgres.Builder> embeddedPostgresCustomizer(Integer randomPort) {
            return builder -> builder.setPort(randomPort);
        }
    }

    @Autowired
    private Integer randomPort;

    @Autowired
    private DataSource dataSource;

    @Test
    public void testDataSource() throws SQLException {
        assertThat(dataSource.unwrap(PGSimpleDataSource.class).getPortNumber()).isEqualTo(randomPort);
    }
}
