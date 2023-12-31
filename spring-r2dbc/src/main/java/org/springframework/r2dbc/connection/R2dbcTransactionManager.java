/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.r2dbc.connection;

import java.time.Duration;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.Result;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager;
import org.springframework.transaction.reactive.GenericReactiveTransaction;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * {@link org.springframework.transaction.ReactiveTransactionManager} implementation
 * for a single R2DBC {@link ConnectionFactory}. This class is capable of working
 * in any environment with any R2DBC driver, as long as the setup uses a
 * {@code ConnectionFactory} as its {@link Connection} factory mechanism.
 * Binds a R2DBC {@code Connection} from the specified {@code ConnectionFactory}
 * to the current subscriber context, potentially allowing for one context-bound
 * {@code Connection} per {@code ConnectionFactory}.
 *
 * <p><b>Note: The {@code ConnectionFactory} that this transaction manager operates
 * on needs to return independent {@code Connection}s.</b> The {@code Connection}s
 * typically come from a connection pool but the {@code ConnectionFactory} must not
 * return specifically scoped or constrained {@code Connection}s. This transaction
 * manager will associate {@code Connection} with context-bound transactions,
 * according to the specified propagation behavior. It assumes that a separate,
 * independent {@code Connection} can be obtained even during an ongoing transaction.
 *
 * <p>Application code is required to retrieve the R2DBC Connection via
 * {@link ConnectionFactoryUtils#getConnection(ConnectionFactory)}
 * instead of a standard R2DBC-style {@link ConnectionFactory#create()} call.
 * Spring classes such as {@code DatabaseClient} use this strategy implicitly.
 * If not used in combination with this transaction manager, the
 * {@link ConnectionFactoryUtils} lookup strategy behaves exactly like the native
 * {@code ConnectionFactory} lookup; it can thus be used in a portable fashion.
 *
 * <p>Alternatively, you can allow application code to work with the lookup pattern
 * {@link ConnectionFactory#create()}, for example for code not aware of Spring.
 * In that case, define a {@link TransactionAwareConnectionFactoryProxy} for your
 * target {@code ConnectionFactory}, and pass that proxy {@code ConnectionFactory}
 * to your DAOs which will automatically participate in Spring-managed transactions
 * when accessing it.
 *
 * @author Mark Paluch
 * @since 5.3
 * @see ConnectionFactoryUtils#getConnection(ConnectionFactory)
 * @see ConnectionFactoryUtils#releaseConnection
 * @see TransactionAwareConnectionFactoryProxy
 */
@SuppressWarnings("serial")
public class R2dbcTransactionManager extends AbstractReactiveTransactionManager implements InitializingBean {

	@Nullable
	private ConnectionFactory connectionFactory;

	private boolean enforceReadOnly = false;


	/**
	 * Create a new {@code R2dbcTransactionManager} instance.
	 * A {@code ConnectionFactory} has to be set to be able to use it.
	 * @see #setConnectionFactory
	 */
	public R2dbcTransactionManager() {}

	/**
	 * Create a new {@code R2dbcTransactionManager} instance.
	 * @param connectionFactory the R2DBC ConnectionFactory to manage transactions for
	 */
	public R2dbcTransactionManager(ConnectionFactory connectionFactory) {
		this();
		setConnectionFactory(connectionFactory);
		afterPropertiesSet();
	}


	/**
	 * Set the R2DBC {@link ConnectionFactory} that this instance should manage transactions
	 * for. This will typically be a locally defined {@code ConnectionFactory}, for example
	 * an R2DBC connection pool.
	 * <p><b>The {@code ConnectionFactory} passed in here needs to return independent
	 * {@link Connection}s.</b> The {@code Connection}s typically come from a connection
	 * pool but the {@code ConnectionFactory} must not return specifically scoped or
	 * constrained {@code Connection}s.
	 */
	public void setConnectionFactory(@Nullable ConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
	}

	/**
	 * Return the R2DBC {@link ConnectionFactory} that this instance manages transactions for.
	 */
	@Nullable
	public ConnectionFactory getConnectionFactory() {
		return this.connectionFactory;
	}

	/**
	 * Obtain the {@link ConnectionFactory} for actual use.
	 * @return the {@code ConnectionFactory} (never {@code null})
	 * @throws IllegalStateException in case of no ConnectionFactory set
	 */
	protected ConnectionFactory obtainConnectionFactory() {
		ConnectionFactory connectionFactory = getConnectionFactory();
		Assert.state(connectionFactory != null, "No ConnectionFactory set");
		return connectionFactory;
	}

	/**
	 * Specify whether to enforce the read-only nature of a transaction (as indicated by
	 * {@link TransactionDefinition#isReadOnly()}) through an explicit statement on the
	 * transactional connection: "SET TRANSACTION READ ONLY" as understood by Oracle,
	 * MySQL and Postgres.
	 * <p>The exact treatment, including any SQL statement executed on the connection,
	 * can be customized through {@link #prepareTransactionalConnection}.
	 * @see #prepareTransactionalConnection
	 */
	public void setEnforceReadOnly(boolean enforceReadOnly) {
		this.enforceReadOnly = enforceReadOnly;
	}

	/**
	 * Return whether to enforce the read-only nature of a transaction through an
	 * explicit statement on the transactional connection.
	 * @see #setEnforceReadOnly
	 */
	public boolean isEnforceReadOnly() {
		return this.enforceReadOnly;
	}

	@Override
	public void afterPropertiesSet() {
		if (getConnectionFactory() == null) {
			throw new IllegalArgumentException("Property 'connectionFactory' is required");
		}
	}

	@Override
	protected Object doGetTransaction(TransactionSynchronizationManager synchronizationManager) {
		ConnectionFactoryTransactionObject txObject = new ConnectionFactoryTransactionObject();
		ConnectionHolder conHolder = (ConnectionHolder) synchronizationManager.getResource(obtainConnectionFactory());
		txObject.setConnectionHolder(conHolder, false);
		return txObject;
	}

	@Override
	protected boolean isExistingTransaction(Object transaction) {
		ConnectionFactoryTransactionObject txObject = (ConnectionFactoryTransactionObject) transaction;
		return (txObject.hasConnectionHolder() && txObject.getConnectionHolder().isTransactionActive());
	}

	@SuppressWarnings("deprecation")
	@Override
	protected Mono<Void> doBegin(TransactionSynchronizationManager synchronizationManager, Object transaction,
			TransactionDefinition definition) {

		ConnectionFactoryTransactionObject txObject = (ConnectionFactoryTransactionObject) transaction;

		return Mono.defer(() -> {
			Mono<Connection> connectionMono;

			if (!txObject.hasConnectionHolder() || txObject.getConnectionHolder().isSynchronizedWithTransaction()) {
				Mono<Connection> newCon = Mono.from(obtainConnectionFactory().create());
				connectionMono = newCon.doOnNext(connection -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Acquired Connection [" + connection + "] for R2DBC transaction");
					}
					txObject.setConnectionHolder(new ConnectionHolder(connection), true);
				});
			}
			else {
				txObject.getConnectionHolder().setSynchronizedWithTransaction(true);
				connectionMono = Mono.just(txObject.getConnectionHolder().getConnection());
			}

			return connectionMono.flatMap(con -> prepareTransactionalConnection(con, definition, transaction)
					.then(Mono.from(con.beginTransaction()))
					.then(prepareTransactionalConnection(con, definition))
					.doOnSuccess(v -> {
						txObject.getConnectionHolder().setTransactionActive(true);
						Duration timeout = determineTimeout(definition);
						if (!timeout.isNegative() && !timeout.isZero()) {
							txObject.getConnectionHolder().setTimeoutInMillis(timeout.toMillis());
						}
						// Bind the connection holder to the thread.
						if (txObject.isNewConnectionHolder()) {
							synchronizationManager.bindResource(obtainConnectionFactory(), txObject.getConnectionHolder());
						}
					}).thenReturn(con).onErrorResume(e -> {
						if (txObject.isNewConnectionHolder()) {
							return ConnectionFactoryUtils.releaseConnection(con, obtainConnectionFactory())
									.doOnTerminate(() -> txObject.setConnectionHolder(null, false))
									.then(Mono.error(e));
						}
						return Mono.error(e);
			})).onErrorResume(e -> {
				CannotCreateTransactionException ex = new CannotCreateTransactionException(
						"Could not open R2DBC Connection for transaction", e);
				return Mono.error(ex);
			});
		}).then();
	}

	/**
	 * Determine the actual timeout to use for the given definition.
	 * Will fall back to this manager's default timeout if the
	 * transaction definition doesn't specify a non-default value.
	 * @param definition the transaction definition
	 * @return the actual timeout to use
	 * @see org.springframework.transaction.TransactionDefinition#getTimeout()
	 */
	protected Duration determineTimeout(TransactionDefinition definition) {
		if (definition.getTimeout() != TransactionDefinition.TIMEOUT_DEFAULT) {
			return Duration.ofSeconds(definition.getTimeout());
		}
		return Duration.ZERO;
	}

	@Override
	protected Mono<Object> doSuspend(TransactionSynchronizationManager synchronizationManager, Object transaction) {
		return Mono.defer(() -> {
			ConnectionFactoryTransactionObject txObject = (ConnectionFactoryTransactionObject) transaction;
			txObject.setConnectionHolder(null);
			return Mono.justOrEmpty(synchronizationManager.unbindResource(obtainConnectionFactory()));
		});
	}

	@Override
	protected Mono<Void> doResume(TransactionSynchronizationManager synchronizationManager,
			@Nullable Object transaction, Object suspendedResources) {

		return Mono.defer(() -> {
			synchronizationManager.bindResource(obtainConnectionFactory(), suspendedResources);
			return Mono.empty();
		});
	}

	@Override
	protected Mono<Void> doCommit(TransactionSynchronizationManager TransactionSynchronizationManager,
			GenericReactiveTransaction status) {

		ConnectionFactoryTransactionObject txObject = (ConnectionFactoryTransactionObject) status.getTransaction();
		Connection connection = txObject.getConnectionHolder().getConnection();
		if (status.isDebug()) {
			logger.debug("Committing R2DBC transaction on Connection [" + connection + "]");
		}
		return Mono.from(connection.commitTransaction())
				.onErrorMap(R2dbcException.class, ex -> translateException("R2DBC commit", ex));
	}

	@Override
	protected Mono<Void> doRollback(TransactionSynchronizationManager TransactionSynchronizationManager,
			GenericReactiveTransaction status) {

		ConnectionFactoryTransactionObject txObject = (ConnectionFactoryTransactionObject) status.getTransaction();
		Connection connection = txObject.getConnectionHolder().getConnection();
		if (status.isDebug()) {
			logger.debug("Rolling back R2DBC transaction on Connection [" + connection + "]");
		}
		return Mono.from(connection.rollbackTransaction())
				.onErrorMap(R2dbcException.class, ex -> translateException("R2DBC rollback", ex));
	}

	@Override
	protected Mono<Void> doSetRollbackOnly(TransactionSynchronizationManager synchronizationManager,
			GenericReactiveTransaction status) {

		return Mono.fromRunnable(() -> {
			ConnectionFactoryTransactionObject txObject = (ConnectionFactoryTransactionObject) status.getTransaction();
			if (status.isDebug()) {
				logger.debug("Setting R2DBC transaction [" + txObject.getConnectionHolder().getConnection() +
						"] rollback-only");
			}
			txObject.setRollbackOnly();
		});
	}

	@Override
	protected Mono<Void> doCleanupAfterCompletion(TransactionSynchronizationManager synchronizationManager,
			Object transaction) {

		return Mono.defer(() -> {
			ConnectionFactoryTransactionObject txObject = (ConnectionFactoryTransactionObject) transaction;

			// Remove the connection holder from the context, if exposed.
			if (txObject.isNewConnectionHolder()) {
				synchronizationManager.unbindResource(obtainConnectionFactory());
			}

			// Reset connection.
			Connection con = txObject.getConnectionHolder().getConnection();

			Mono<Void> afterCleanup = Mono.empty();

			if (txObject.isMustRestoreAutoCommit()) {
				Mono<Void> restoreAutoCommitStep = safeCleanupStep(
						"doCleanupAfterCompletion when restoring autocommit", Mono.from(con.setAutoCommit(true)));
				afterCleanup = afterCleanup.then(restoreAutoCommitStep);
			}

			if (txObject.getPreviousIsolationLevel() != null) {
				Mono<Void> restoreIsolationStep = safeCleanupStep(
						"doCleanupAfterCompletion when restoring isolation level",
						Mono.from(con.setTransactionIsolationLevel(txObject.getPreviousIsolationLevel())));
				afterCleanup = afterCleanup.then(restoreIsolationStep);
			}

			Mono<Void> releaseConnectionStep = Mono.defer(() -> {
				try {
					if (txObject.isNewConnectionHolder()) {
						if (logger.isDebugEnabled()) {
							logger.debug("Releasing R2DBC Connection [" + con + "] after transaction");
						}
						return safeCleanupStep("doCleanupAfterCompletion when releasing R2DBC Connection",
								ConnectionFactoryUtils.releaseConnection(con, obtainConnectionFactory()));
					}
				}
				finally {
					txObject.getConnectionHolder().clear();
				}
				return Mono.empty();
			});
			return afterCleanup.then(releaseConnectionStep);
		});
	}

	private Mono<Void> safeCleanupStep(String stepDescription, Mono<Void> stepMono) {
		if (!logger.isDebugEnabled()) {
			return stepMono.onErrorComplete();
		}
		else {
			return stepMono.doOnError(e ->
							logger.debug(String.format("Error ignored during %s: %s", stepDescription, e)))
					.onErrorComplete();
		}
	}

	/**
	 * Prepare the transactional {@link Connection} right before transaction begin.
	 * @deprecated in favor of {@link #prepareTransactionalConnection(Connection, TransactionDefinition)}
	 * since this variant gets called too early (before transaction begin) for read-only customization
	 */
	@Deprecated
	protected Mono<Void> prepareTransactionalConnection(
			Connection con, TransactionDefinition definition, Object transaction) {

		ConnectionFactoryTransactionObject txObject = (ConnectionFactoryTransactionObject) transaction;
		Mono<Void> prepare = Mono.empty();

		// Apply specific isolation level, if any.
		IsolationLevel isolationLevelToUse = resolveIsolationLevel(definition.getIsolationLevel());
		if (isolationLevelToUse != null && definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {

			if (logger.isDebugEnabled()) {
				logger.debug("Changing isolation level of R2DBC Connection [" + con + "] to " + isolationLevelToUse.asSql());
			}
			IsolationLevel currentIsolation = con.getTransactionIsolationLevel();
			if (!currentIsolation.asSql().equalsIgnoreCase(isolationLevelToUse.asSql())) {

				txObject.setPreviousIsolationLevel(currentIsolation);
				prepare = prepare.then(Mono.from(con.setTransactionIsolationLevel(isolationLevelToUse)));
			}
		}

		// Switch to manual commit if necessary. This is very expensive in some R2DBC drivers,
		// so we don't want to do it unnecessarily (for example if we've explicitly
		// configured the connection pool to set it already).
		if (con.isAutoCommit()) {
			txObject.setMustRestoreAutoCommit(true);
			if (logger.isDebugEnabled()) {
				logger.debug("Switching R2DBC Connection [" + con + "] to manual commit");
			}
			prepare = prepare.then(Mono.from(con.setAutoCommit(false)));
		}

		return prepare;
	}

	/**
	 * Prepare the transactional {@link Connection} right after transaction begin.
	 * <p>The default implementation executes a "SET TRANSACTION READ ONLY" statement if the
	 * {@link #setEnforceReadOnly "enforceReadOnly"} flag is set to {@code true} and the
	 * transaction definition indicates a read-only transaction.
	 * <p>The "SET TRANSACTION READ ONLY" is understood by Oracle, MySQL and Postgres
	 * and may work with other databases as well. If you'd like to adapt this treatment,
	 * override this method accordingly.
	 * @param con the transactional R2DBC Connection
	 * @param definition the current transaction definition
	 * @since 5.3.22
	 * @see #setEnforceReadOnly
	 */
	protected Mono<Void> prepareTransactionalConnection(Connection con, TransactionDefinition definition) {
		Mono<Void> prepare = Mono.empty();
		if (isEnforceReadOnly() && definition.isReadOnly()) {
			prepare = Mono.from(con.createStatement("SET TRANSACTION READ ONLY").execute())
					.flatMapMany(Result::getRowsUpdated)
					.then();
		}
		return prepare;
	}

	/**
	 * Resolve the {@linkplain TransactionDefinition#getIsolationLevel() isolation level constant} to a R2DBC
	 * {@link IsolationLevel}. If you'd like to extend isolation level translation for vendor-specific
	 * {@code IsolationLevel}s, override this method accordingly.
	 * @param isolationLevel the isolation level to translate.
	 * @return the resolved isolation level. Can be {@code null} if not resolvable or the isolation level
	 * should remain {@link TransactionDefinition#ISOLATION_DEFAULT default}.
	 * @see TransactionDefinition#getIsolationLevel()
	 */
	@Nullable
	protected IsolationLevel resolveIsolationLevel(int isolationLevel) {
		switch (isolationLevel) {
			case TransactionDefinition.ISOLATION_READ_COMMITTED:
				return IsolationLevel.READ_COMMITTED;
			case TransactionDefinition.ISOLATION_READ_UNCOMMITTED:
				return IsolationLevel.READ_UNCOMMITTED;
			case TransactionDefinition.ISOLATION_REPEATABLE_READ:
				return IsolationLevel.REPEATABLE_READ;
			case TransactionDefinition.ISOLATION_SERIALIZABLE:
				return IsolationLevel.SERIALIZABLE;
		}
		return null;
	}

	/**
	 * Translate the given R2DBC commit/rollback exception to a common Spring exception to propagate
	 * from the {@link #commit}/{@link #rollback} call.
	 * @param task the task description (commit or rollback).
	 * @param ex the SQLException thrown from commit/rollback.
	 * @return the translated exception to emit
	 */
	protected RuntimeException translateException(String task, R2dbcException ex) {
		return ConnectionFactoryUtils.convertR2dbcException(task, null, ex);
	}


	/**
	 * ConnectionFactory transaction object, representing a ConnectionHolder.
	 * Used as transaction object by R2dbcTransactionManager.
	 */
	private static class ConnectionFactoryTransactionObject {

		@Nullable
		private ConnectionHolder connectionHolder;

		@Nullable
		private IsolationLevel previousIsolationLevel;

		private boolean newConnectionHolder;

		private boolean mustRestoreAutoCommit;

		void setConnectionHolder(@Nullable ConnectionHolder connectionHolder, boolean newConnectionHolder) {
			setConnectionHolder(connectionHolder);
			this.newConnectionHolder = newConnectionHolder;
		}

		boolean isNewConnectionHolder() {
			return this.newConnectionHolder;
		}

		void setRollbackOnly() {
			getConnectionHolder().setRollbackOnly();
		}

		public void setConnectionHolder(@Nullable ConnectionHolder connectionHolder) {
			this.connectionHolder = connectionHolder;
		}

		public ConnectionHolder getConnectionHolder() {
			Assert.state(this.connectionHolder != null, "No ConnectionHolder available");
			return this.connectionHolder;
		}

		public boolean hasConnectionHolder() {
			return (this.connectionHolder != null);
		}

		public void setPreviousIsolationLevel(@Nullable IsolationLevel previousIsolationLevel) {
			this.previousIsolationLevel = previousIsolationLevel;
		}

		@Nullable
		public IsolationLevel getPreviousIsolationLevel() {
			return this.previousIsolationLevel;
		}

		public void setMustRestoreAutoCommit(boolean mustRestoreAutoCommit) {
			this.mustRestoreAutoCommit = mustRestoreAutoCommit;
		}

		public boolean isMustRestoreAutoCommit() {
			return this.mustRestoreAutoCommit;
		}
	}

}
