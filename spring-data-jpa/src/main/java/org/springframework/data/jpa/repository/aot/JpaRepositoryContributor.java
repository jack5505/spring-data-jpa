/*
 * Copyright 2025 the original author or authors.
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
package org.springframework.data.jpa.repository.aot;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.spi.PersistenceUnitInfo;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.data.jpa.provider.PersistenceProvider;
import org.springframework.data.jpa.provider.QueryExtractor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.query.JpaEntityMetadata;
import org.springframework.data.jpa.repository.query.JpaParameters;
import org.springframework.data.jpa.repository.query.JpaQueryMethod;
import org.springframework.data.jpa.repository.query.Procedure;
import org.springframework.data.jpa.repository.query.QueryEnhancerSelector;
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.aot.generate.AotRepositoryClassBuilder;
import org.springframework.data.repository.aot.generate.AotRepositoryConstructorBuilder;
import org.springframework.data.repository.aot.generate.MethodContributor;
import org.springframework.data.repository.aot.generate.QueryMetadata;
import org.springframework.data.repository.aot.generate.RepositoryContributor;
import org.springframework.data.repository.config.AotRepositoryContext;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.query.ParametersSource;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.data.util.TypeInformation;
import org.springframework.javapoet.CodeBlock;
import org.springframework.javapoet.TypeName;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * JPA-specific {@link RepositoryContributor} contributing an AOT repository fragment using the {@link EntityManager}
 * directly to run queries.
 * <p>
 * The underlying {@link jakarta.persistence.metamodel.Metamodel} requires Hibernate to build metamodel information.
 *
 * @author Christoph Strobl
 * @author Mark Paluch
 * @since 4.0
 */
public class JpaRepositoryContributor extends RepositoryContributor {

	private final Metamodel metamodel;
	private final PersistenceUnitUtil persistenceUnitUtil;
	private final PersistenceProvider persistenceProvider;
	private final QueriesFactory queriesFactory;
	private final EntityGraphLookup entityGraphLookup;
	private final AotRepositoryContext context;

	public JpaRepositoryContributor(AotRepositoryContext repositoryContext) {
		this(repositoryContext, new AotMetamodel(repositoryContext));
	}

	public JpaRepositoryContributor(AotRepositoryContext repositoryContext, PersistenceUnitInfo unitInfo) {
		this(repositoryContext, new AotMetamodel(unitInfo));
	}

	public JpaRepositoryContributor(AotRepositoryContext repositoryContext, PersistenceManagedTypes managedTypes) {
		this(repositoryContext, new AotMetamodel(managedTypes));
	}

	public JpaRepositoryContributor(AotRepositoryContext repositoryContext, EntityManagerFactory entityManagerFactory) {
		this(repositoryContext, entityManagerFactory, entityManagerFactory.getMetamodel());
	}

	private JpaRepositoryContributor(AotRepositoryContext repositoryContext, AotMetamodel metamodel) {
		this(repositoryContext, metamodel.getEntityManagerFactory(), metamodel);
	}

	private JpaRepositoryContributor(AotRepositoryContext repositoryContext, EntityManagerFactory entityManagerFactory,
			Metamodel metamodel) {

		super(repositoryContext);

		this.metamodel = metamodel;
		this.persistenceUnitUtil = entityManagerFactory.getPersistenceUnitUtil();
		this.persistenceProvider = PersistenceProvider.fromEntityManagerFactory(entityManagerFactory);
		this.queriesFactory = new QueriesFactory(repositoryContext.getConfigurationSource(), entityManagerFactory,
				repositoryContext.getRequiredClassLoader());
		this.entityGraphLookup = new EntityGraphLookup(entityManagerFactory);
		this.context = repositoryContext;
	}

	@Override
	protected void customizeClass(AotRepositoryClassBuilder classBuilder) {
		classBuilder.customize(builder -> builder.superclass(TypeName.get(AotRepositoryFragmentSupport.class)));
	}

	@Override
	protected void customizeConstructor(AotRepositoryConstructorBuilder constructorBuilder) {

		String entityManagerFactoryRef = getEntityManagerFactoryRef();

		constructorBuilder.addParameter("entityManager", EntityManager.class, customizer -> {

			customizer.bindToField().origin(
					StringUtils.hasText(entityManagerFactoryRef)
							? new RuntimeBeanReference(entityManagerFactoryRef, EntityManager.class)
							: new RuntimeBeanReference(EntityManager.class));
		});

		constructorBuilder.addParameter("context", RepositoryFactoryBeanSupport.FragmentCreationContext.class);

		Optional<Class<QueryEnhancerSelector>> queryEnhancerSelector = getQueryEnhancerSelectorClass();

		constructorBuilder.customize(builder -> {

			if (queryEnhancerSelector.isPresent()) {
				builder.addStatement("super(new T$(), context)", queryEnhancerSelector.get());
			} else {
				builder.addStatement("super($T.DEFAULT_SELECTOR, context)", QueryEnhancerSelector.class);
			}
		});
	}

	private @Nullable String getEntityManagerFactoryRef() {
		return context.getConfigurationSource().getAttribute("entityManagerFactoryRef")
				.filter(it -> !"entityManagerFactory".equals(it)).orElse(null);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Optional<Class<QueryEnhancerSelector>> getQueryEnhancerSelectorClass() {
		return (Optional) context.getConfigurationSource().getAttribute("queryEnhancerSelector", Class.class)
				.filter(it -> !it.equals(QueryEnhancerSelector.DefaultQueryEnhancerSelector.class));
	}

	@Override
	protected @Nullable MethodContributor<? extends QueryMethod> contributeQueryMethod(Method method) {

		JpaEntityMetadata<?> entityInformation = JpaEntityInformationSupport
				.getEntityInformation(getRepositoryInformation().getDomainType(), metamodel, persistenceUnitUtil);
		AotJpaQueryMethod queryMethod = new AotJpaQueryMethod(method, getRepositoryInformation(), entityInformation,
				getProjectionFactory(), persistenceProvider, JpaParameters::new);

		Optional<Class<QueryEnhancerSelector>> queryEnhancerSelectorClass = getQueryEnhancerSelectorClass();
		QueryEnhancerSelector selector = queryEnhancerSelectorClass.map(BeanUtils::instantiateClass)
				.orElse(QueryEnhancerSelector.DEFAULT_SELECTOR);

		// no stored procedures for now.
		if (queryMethod.isProcedureQuery()) {

			Procedure procedure = AnnotatedElementUtils.findMergedAnnotation(method, Procedure.class);

			MethodContributor.QueryMethodMetadataContributorBuilder<JpaQueryMethod> builder = MethodContributor
					.forQueryMethod(queryMethod);

			if (procedure != null) {

				if (StringUtils.hasText(procedure.name())) {
					return builder.metadataOnly(new NamedStoredProcedureMetadata(procedure.name()));
				}

				if (StringUtils.hasText(procedure.procedureName())) {
					return builder.metadataOnly(new StoredProcedureMetadata(procedure.procedureName()));
				}

				if (StringUtils.hasText(procedure.value())) {
					return builder.metadataOnly(new StoredProcedureMetadata(procedure.value()));
				}
			}

			// TODO: Better fallback.
			return null;
		}

		ReturnedType returnedType = queryMethod.getResultProcessor().getReturnedType();
		JpaParameters parameters = queryMethod.getParameters();

		MergedAnnotation<Query> query = MergedAnnotations.from(method).get(Query.class);

		AotQueries aotQueries = queriesFactory.createQueries(getRepositoryInformation(), returnedType, selector, query,
				queryMethod);

		// no KeysetScrolling for now.
		if (parameters.hasScrollPositionParameter() || queryMethod.isScrollQuery()) {
			return MethodContributor.forQueryMethod(queryMethod)
					.metadataOnly(aotQueries.toMetadata(queryMethod.isPageQuery()));
		}

		// no dynamic projections.
		if (parameters.hasDynamicProjection()) {
			return MethodContributor.forQueryMethod(queryMethod)
					.metadataOnly(aotQueries.toMetadata(queryMethod.isPageQuery()));
		}

		if (queryMethod.isModifyingQuery()) {

			TypeInformation<?> returnType = getRepositoryInformation().getReturnType(method);

			boolean returnsCount = JpaCodeBlocks.QueryExecutionBlockBuilder.returnsModifying(returnType.getType());
			boolean isVoid = ClassUtils.isVoidType(returnType.getType());

			if (!returnsCount && !isVoid) {
				return MethodContributor.forQueryMethod(queryMethod)
						.metadataOnly(aotQueries.toMetadata(queryMethod.isPageQuery()));
			}
		}

		return MethodContributor.forQueryMethod(queryMethod).withMetadata(aotQueries.toMetadata(queryMethod.isPageQuery()))
				.contribute(context -> {

					CodeBlock.Builder body = CodeBlock.builder();

					MergedAnnotation<NativeQuery> nativeQuery = context.getAnnotation(NativeQuery.class);
					MergedAnnotation<QueryHints> queryHints = context.getAnnotation(QueryHints.class);
					MergedAnnotation<EntityGraph> entityGraph = context.getAnnotation(EntityGraph.class);
					MergedAnnotation<Modifying> modifying = context.getAnnotation(Modifying.class);

					AotEntityGraph aotEntityGraph = entityGraphLookup.findEntityGraph(entityGraph, getRepositoryInformation(),
							returnedType, queryMethod);

					body.add(JpaCodeBlocks.queryBuilder(context, queryMethod).filter(aotQueries)
							.queryReturnType(QueriesFactory.getQueryReturnType(aotQueries.result(), returnedType, context))
							.nativeQuery(nativeQuery).queryHints(queryHints).entityGraph(aotEntityGraph)
							.queryRewriter(query.isPresent() ? query.getClass("queryRewriter") : null).build());

					body.add(JpaCodeBlocks.executionBuilder(context, queryMethod).modifying(modifying).query(aotQueries.result())
							.build());

					return body.build();
				});
	}

	public Metamodel getMetamodel() {
		return metamodel;
	}

	record StoredProcedureMetadata(String procedure) implements QueryMetadata {

		@Override
		public Map<String, Object> serialize() {
			return Map.of("procedure", procedure());
		}
	}

	record NamedStoredProcedureMetadata(String procedureName) implements QueryMetadata {

		@Override
		public Map<String, Object> serialize() {
			return Map.of("procedure-name", procedureName());
		}
	}

	/**
	 * AOT extension to {@link JpaQueryMethod} providing a metamodel backed {@link JpaEntityMetadata} object.
	 */
	static class AotJpaQueryMethod extends JpaQueryMethod {

		private final JpaEntityMetadata<?> entityMetadata;

		public AotJpaQueryMethod(Method method, RepositoryMetadata metadata, JpaEntityMetadata<?> entityMetadata,
				ProjectionFactory factory, QueryExtractor extractor,
				Function<ParametersSource, JpaParameters> parametersFunction) {

			super(method, metadata, factory, extractor, parametersFunction);

			this.entityMetadata = entityMetadata;
		}

		@Override
		public JpaEntityMetadata<?> getEntityInformation() {
			return this.entityMetadata;
		}

	}

}
