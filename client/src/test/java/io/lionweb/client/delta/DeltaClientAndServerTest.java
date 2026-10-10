package io.lionweb.client.delta;

import static org.junit.jupiter.api.Assertions.*;

import io.lionweb.LionWebVersion;
import io.lionweb.client.api.HistorySupport;
import io.lionweb.client.api.RepositoryConfiguration;
import io.lionweb.client.delta.messages.events.StandardErrorCode;
import io.lionweb.client.inmemory.InMemoryServer;
import io.lionweb.language.*;
import io.lionweb.serialization.JsonSerialization;
import io.lionweb.serialization.SerializationProvider;
import io.lionweb.utils.ModelComparator;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DeltaClientAndServerTest extends AbstractDeltaProtocolTest {

  @Test
  public void simpleSynchronizationOfNodesInstances() {
    InMemoryServer server = new InMemoryServer();
    server.createRepository(
        new RepositoryConfiguration("MyRepo", LionWebVersion.v2024_1, HistorySupport.DISABLED));

    JsonSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1);
    Language language1 = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", language1, serialization);

    Language language2 =
        (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", serialization);
    Assertions.assertNotNull(language2);

    assertEquals(language1, language2);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();
    client1.monitorPartition(language1);
    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-2");
    client2.sendSignOnRequest();
    client2.monitorPartition(language2);

    assertEquals("Language A", language1.getName());
    assertEquals("Language A", language2.getName());

    language1.setName("Language B");
    assertEquals("Language B", language1.getName());
    assertEquals("Language B", language2.getName());

    language2.setName("Language C");
    assertEquals("Language C", language1.getName());
    assertEquals("Language C", language2.getName());

    language1.setName("Language A");
    assertEquals("Language A", language1.getName());
    assertEquals("Language A", language2.getName());
  }

  @Test
  public void changingUnexistingNodeCauseError() {
    InMemoryServer server = new InMemoryServer();
    server.createRepository(
        new RepositoryConfiguration("MyRepo", LionWebVersion.v2024_1, HistorySupport.DISABLED));

    JsonSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1);
    Language language1 = new Language("Language A", "lang-a", "lang-a-key");
    // We do NOT create the partition on the repository

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client = new DeltaClient(channel, "my-client-1");
    client.sendSignOnRequest();

    client.monitorPartition(language1);
    try {
      language1.setName("Language B");
    } catch (ErrorEventReceivedException e) {
      assertEquals(StandardErrorCode.UNKNOWN_NODE.code, e.getCode());
      assertEquals("Node with id lang-a not found", e.getErrorMessage());
      return;
    }
    fail("Expected exception not thrown");
  }

  @Test
  public void addingChildren() {
    InMemoryServer server = new InMemoryServer();
    server.createRepository(
        new RepositoryConfiguration("MyRepo", LionWebVersion.v2024_1, HistorySupport.DISABLED));

    JsonSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1);
    Language language1 = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", language1, serialization);

    Language language2 =
        (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", serialization);
    Assertions.assertNotNull(language2);

    assertEquals(language1, language2);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();

    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-2");
    client2.sendSignOnRequest();

    client1.monitorPartition(language1);
    client2.monitorPartition(language2);

    assertEquals(Collections.emptyList(), language1.getElements());
    assertEquals(Collections.emptyList(), language2.getElements());

    Concept concept1 = new Concept(language1, "Concept A", "concept-a", "a");
    language1.addElement(concept1);
    assertTrue(
        ModelComparator.areEquivalent(
            Collections.singletonList(concept1), language1.getElements()));
    assertTrue(
        ModelComparator.areEquivalent(
            Collections.singletonList(concept1), language2.getElements()));
  }

  @Test
  public void removingChildren() {
    InMemoryServer server = new InMemoryServer();
    server.createRepository(
        new RepositoryConfiguration("MyRepo", LionWebVersion.v2024_1, HistorySupport.DISABLED));

    JsonSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1);
    Language language1 = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", language1, serialization);

    Language language2 =
        (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", serialization);
    Assertions.assertNotNull(language2);

    assertEquals(language1, language2);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();

    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-2");
    client2.sendSignOnRequest();

    client1.monitorPartition(language1);
    client2.monitorPartition(language2);

    Concept concept1 = new Concept(language1, "Concept A", "concept-a", "a");
    language1.addElement(concept1);
    Concept concept2 = new Concept(language1, "Concept B", "concept-b", "b");
    language1.addElement(concept2);
    Concept concept3 = new Concept(language1, "Concept C", "concept-c", "c");
    language1.addElement(concept3);

    assertEquals(Arrays.asList(concept1, concept2, concept3), language1.getElements());
    assertEquals(Arrays.asList(concept1, concept2, concept3), language2.getElements());

    language1.removeChild(concept2);

    assertEquals(Arrays.asList(concept1, concept3), language1.getElements());
    assertEquals(Arrays.asList(concept1, concept3), language2.getElements());

    language1.removeChild(concept3);

    assertEquals(Arrays.asList(concept1), language1.getElements());
    assertEquals(Arrays.asList(concept1), language2.getElements());

    language1.removeChild(concept1);

    assertEquals(Arrays.asList(), language1.getElements());
    assertEquals(Arrays.asList(), language2.getElements());
  }

  @Test
  public void variousOperations() {
    InMemoryServer server = new InMemoryServer();
    server.createRepository(
        new RepositoryConfiguration("MyRepo", LionWebVersion.v2024_1, HistorySupport.DISABLED));

    JsonSerialization serialization =
        SerializationProvider.getStandardJsonSerialization(LionWebVersion.v2024_1);
    Language language1 = new Language("Language A", "lang-a", "lang-a-key");
    server.createPartition("MyRepo", language1, serialization);

    Language language2 =
        (Language) server.retrieveAsClassifierInstance("MyRepo", "lang-a", serialization);
    Assertions.assertNotNull(language2);

    assertEquals(language1, language2);

    DeltaChannel channel = new InMemoryDeltaChannel();
    server.monitorDeltaChannel("MyRepo", channel);

    DeltaClient client1 = new DeltaClient(channel, "my-client-1");
    client1.sendSignOnRequest();

    DeltaClient client2 = new DeltaClient(prepareChannel(server), "my-client-2");
    client2.sendSignOnRequest();

    client1.monitorPartition(language1);
    client2.monitorPartition(language2);

    // HERE DO A LOT OF OPERATIONS CREATING A LANGUAGE AND CHANGING IT

    // 1. Change language name multiple times
    language1.setName("Language Modified");
    language1.setName("Business Domain Language");
    language1.setName("Enterprise Modeling Language");

    // 2. Create enumerations
    Enumeration statusEnum = new Enumeration(language1, "Status", "status-enum").setKey("status");
    language1.addElement(statusEnum);

    EnumerationLiteral activeStatus =
        new EnumerationLiteral(statusEnum, "Active", "active-literal").setKey("active");
    statusEnum.addLiteral(activeStatus);
    EnumerationLiteral inactiveStatus =
        new EnumerationLiteral(statusEnum, "Inactive", "inactive-literal").setKey("inactive");
    statusEnum.addLiteral(inactiveStatus);
    EnumerationLiteral pendingStatus =
        new EnumerationLiteral(statusEnum, "Pending", "pending-literal").setKey("pending");
    statusEnum.addLiteral(pendingStatus);

    // Add another enumeration
    Enumeration priorityEnum =
        new Enumeration(language1, "Priority", "priority-enum").setKey("priority");
    language1.addElement(priorityEnum);

    EnumerationLiteral highPriority =
        new EnumerationLiteral(priorityEnum, "High", "high-literal").setKey("high");
    priorityEnum.addLiteral(highPriority);
    EnumerationLiteral mediumPriority =
        new EnumerationLiteral(priorityEnum, "Medium", "medium-literal").setKey("medium");
    priorityEnum.addLiteral(mediumPriority);
    EnumerationLiteral lowPriority =
        new EnumerationLiteral(priorityEnum, "Low", "low-literal").setKey("low");
    priorityEnum.addLiteral(lowPriority);

    // 3. Create interfaces
    Interface namedInterface = new Interface(language1, "Named", "named-interface", "named");
    language1.addElement(namedInterface);

    Interface identifiableInterface =
        new Interface(language1, "Identifiable", "identifiable-interface", "identifiable");
    language1.addElement(identifiableInterface);

    Interface auditableInterface =
        new Interface(language1, "Auditable", "auditable-interface", "auditable");
    language1.addElement(auditableInterface);

    Interface timestampedInterface =
        new Interface(language1, "Timestamped", "timestamped-interface", "timestamped");
    language1.addElement(timestampedInterface);

    // 4. Make interfaces extend other interfaces
    auditableInterface.addExtendedInterface(timestampedInterface);
    namedInterface.addExtendedInterface(identifiableInterface);

    // 5. Add features to interfaces
    Property nameProperty = new Property("name", namedInterface, "name-id");
    nameProperty.setType(LionCoreBuiltins.getString());
    namedInterface.addFeature(nameProperty);

    Property idProperty = new Property("id", identifiableInterface, "id0d");
    idProperty.setType(LionCoreBuiltins.getString());
    identifiableInterface.addFeature(idProperty);

    Property createdAtProperty = new Property("createdAt", timestampedInterface, "createdAt-id");
    createdAtProperty.setType(LionCoreBuiltins.getString());
    timestampedInterface.addFeature(createdAtProperty);

    Property modifiedAtProperty = new Property("modifiedAt", timestampedInterface, "modifiedAt-id");
    modifiedAtProperty.setType(LionCoreBuiltins.getString());
    timestampedInterface.addFeature(modifiedAtProperty);

    // 6. Create concepts
    Concept personConcept = new Concept(language1, "Person", "person-concept", "person");
    language1.addElement(personConcept);

    Concept companyConcept = new Concept(language1, "Company", "company-concept", "company");
    language1.addElement(companyConcept);

    Concept addressConcept = new Concept(language1, "Address", "address-concept", "address");
    language1.addElement(addressConcept);

    Concept baseConcept = new Concept(language1, "BaseEntity", "base-entity-concept", "baseEntity");
    language1.addElement(baseConcept);

    // 7. Make concepts implement interfaces
    personConcept.addImplementedInterface(namedInterface);
    personConcept.addImplementedInterface(auditableInterface);

    companyConcept.addImplementedInterface(namedInterface);
    companyConcept.addImplementedInterface(identifiableInterface);

    addressConcept.addImplementedInterface(timestampedInterface);

    baseConcept.addImplementedInterface(identifiableInterface);
    baseConcept.addImplementedInterface(auditableInterface);

    // 8. Set up concept inheritance
    personConcept.setExtendedConcept(baseConcept);
    companyConcept.setExtendedConcept(baseConcept);

    // 9. Add features to concepts
    Property ageProperty = new Property("age", personConcept, "age-id");
    ageProperty.setType(LionCoreBuiltins.getInteger());
    personConcept.addFeature(ageProperty);

    Property emailProperty = new Property("email", personConcept, "email-id");
    emailProperty.setType(LionCoreBuiltins.getString());
    personConcept.addFeature(emailProperty);

    Property statusProperty = new Property("status", personConcept, "status-id");
    statusProperty.setType(statusEnum);
    personConcept.addFeature(statusProperty);

    Property employeeCountProperty =
        new Property("employeeCount", companyConcept, "employeeCount-id");
    employeeCountProperty.setType(LionCoreBuiltins.getInteger());
    companyConcept.addFeature(employeeCountProperty);

    Property streetProperty = new Property("street", addressConcept, "street-id");
    streetProperty.setType(LionCoreBuiltins.getString());
    addressConcept.addFeature(streetProperty);

    Property cityProperty = new Property("city", addressConcept, "city-id");
    cityProperty.setType(LionCoreBuiltins.getString());
    addressConcept.addFeature(cityProperty);

    // 10. Add containment references
    Containment addressesContainment = new Containment("addresses", personConcept, "addresses-id");
    addressesContainment.setType(addressConcept);
    addressesContainment.setMultiple(true);
    personConcept.addFeature(addressesContainment);

    Containment employeesContainment = new Containment("employees", companyConcept, "employees-id");
    employeesContainment.setType(personConcept);
    employeesContainment.setMultiple(true);
    companyConcept.addFeature(employeesContainment);

    // 11. Add regular references
    Reference companyReference = new Reference("employer", personConcept, "employer-id");
    companyReference.setType(companyConcept);
    companyReference.setOptional(true);
    personConcept.addFeature(companyReference);

    // 12. Move features between concepts
    personConcept.removeFeature(emailProperty);
    baseConcept.addFeature(emailProperty);

    // 13. Modify feature properties
    ageProperty.setOptional(true);
    statusProperty.setOptional(false);
    employeeCountProperty.setOptional(true);

    // 14. Add more enumeration literals
    EnumerationLiteral archivedStatus =
        new EnumerationLiteral(statusEnum, "Archived", "archived-id");
    statusEnum.addLiteral(archivedStatus);

    // Remove and re-add enumeration literal
    statusEnum.removeChild(pendingStatus);
    EnumerationLiteral reviewingStatus =
        new EnumerationLiteral(statusEnum, "Reviewing", "reviewing-id");
    statusEnum.addLiteral(reviewingStatus);

    // 15. Modify interface hierarchy
    Interface versionedInterface =
        new Interface(language1, "Versioned", "versioned-interface", "versioned");
    language1.addElement(versionedInterface);

    Property versionProperty = new Property("version", versionedInterface, "version-id");
    versionProperty.setType(LionCoreBuiltins.getInteger());
    versionedInterface.addFeature(versionProperty);

    auditableInterface.addExtendedInterface(versionedInterface);

    // 16. Create abstract concepts
    Concept documentConcept = new Concept(language1, "Document", "document-concept", "document");
    documentConcept.setAbstract(true);
    language1.addElement(documentConcept);

    documentConcept.addImplementedInterface(namedInterface);
    documentConcept.addImplementedInterface(versionedInterface);

    Concept reportConcept = new Concept(language1, "Report", "report-concept", "report");
    language1.addElement(reportConcept);
    reportConcept.setExtendedConcept(documentConcept);

    Concept contractConcept = new Concept(language1, "Contract", "contract-concept", "contract");
    language1.addElement(contractConcept);
    contractConcept.setExtendedConcept(documentConcept);

    // 17. Add features with different cardinalities
    Property tagsProperty = new Property("tags", documentConcept, "tags-id");
    tagsProperty.setType(LionCoreBuiltins.getString());
    documentConcept.addFeature(tagsProperty);

    Property priorityProperty = new Property("priority", reportConcept, "priority-id");
    priorityProperty.setType(priorityEnum);
    reportConcept.addFeature(priorityProperty);

    // 18. Move features within the same concept (change order)
    personConcept.removeFeature(ageProperty);
    personConcept.removeFeature(statusProperty);
    personConcept.addFeature(statusProperty);
    personConcept.addFeature(ageProperty);

    // 19. Create complex reference relationships
    Reference authorReference = new Reference("author", documentConcept, "author-id");
    authorReference.setType(personConcept);
    documentConcept.addFeature(authorReference);

    Reference clientReference = new Reference("client", contractConcept, "client-id");
    clientReference.setType(companyConcept);
    contractConcept.addFeature(clientReference);

    // 20. Modify existing elements
    statusEnum.setName("EntityStatus");
    priorityEnum.setName("TaskPriority");

    activeStatus.setName("ACTIVE");
    inactiveStatus.setName("INACTIVE");

    namedInterface.setName("NamedEntity");
    identifiableInterface.setName("UniqueEntity");

    // 21. Remove and re-add features with modifications
    companyConcept.removeFeature(employeeCountProperty);
    Property staffSizeProperty = new Property("staffSize", companyConcept, "staffSize-id");
    staffSizeProperty.setType(LionCoreBuiltins.getInteger());
    staffSizeProperty.setOptional(false);
    companyConcept.addFeature(staffSizeProperty);

    // 22. Change concept inheritance
    Concept organizationConcept =
        new Concept(language1, "Organization", "organization-concept", "organization");
    language1.addElement(organizationConcept);
    organizationConcept.setExtendedConcept(baseConcept);
    organizationConcept.addImplementedInterface(namedInterface);

    companyConcept.setExtendedConcept(organizationConcept);

    // 23. Add final modifications
    Property descriptionProperty =
        new Property("description", organizationConcept, "description-id");
    descriptionProperty.setType(LionCoreBuiltins.getString());
    descriptionProperty.setOptional(true);
    organizationConcept.addFeature(descriptionProperty);

    // 24. Final language name change
    language1.setName("Complete Enterprise Domain Language");

    assertEquals(language1, language2);
  }
}
