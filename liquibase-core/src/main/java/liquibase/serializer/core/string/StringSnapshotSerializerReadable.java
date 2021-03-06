package liquibase.serializer.core.string;

import liquibase.database.Database;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.serializer.LiquibaseSerializable;
import liquibase.serializer.SnapshotSerializer;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotControl;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Catalog;
import liquibase.structure.core.Column;
import liquibase.structure.core.Schema;
import liquibase.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

public class StringSnapshotSerializerReadable implements SnapshotSerializer {

    private static final int INDENT_LENGTH = 4;
    private int expandDepth = 1;

    @Override
    public String[] getValidFileExtensions() {
        return new String[]{"txt"};
    }

    @Override
    public String serialize(LiquibaseSerializable object, boolean pretty) {
        try {
            StringBuilder buffer = new StringBuilder();
            DatabaseSnapshot snapshot = ((DatabaseSnapshot) object);
            Database database = snapshot.getDatabase();

            buffer.append("Database snapshot for ").append(database.getConnection().getURL()).append("\n");
            addDivider(buffer);
            buffer.append("Database type: ").append(database.getDatabaseProductName()).append("\n");
            buffer.append("Database version: ").append(database.getDatabaseProductVersion()).append("\n");
            buffer.append("Database user: ").append(database.getConnection().getConnectionUserName()).append("\n");

            SnapshotControl snapshotControl = snapshot.getSnapshotControl();
            List<Class> includedTypes = sort(snapshotControl.getTypesToInclude());

            buffer.append("Included types:\n" ).append(StringUtils.indent(StringUtils.join(includedTypes, "\n", new StringUtils.StringUtilsFormatter<Class>() {
                @Override
                public String toString(Class obj) {
                    return obj.getName();
                }
            }))).append("\n");


            List<Schema> schemas = sort(snapshot.get(Schema.class), new Comparator<Schema>() {
                @Override
                public int compare(Schema o1, Schema o2) {
                    return o1.toString().compareTo(o2.toString());
                }
            });

            for (Schema schema : schemas) {
                if (database.supportsSchemas()) {
                    buffer.append("\nCatalog & Schema: ").append(schema.getCatalogName()).append(" / ").append(schema.getName()).append("\n");
                } else {
                    buffer.append("\nCatalog: ").append(schema.getCatalogName()).append("\n");
                }

                StringBuilder catalogBuffer = new StringBuilder();
                for (Class type : includedTypes) {
                    if (type.equals(Schema.class) || type.equals(Catalog.class) || type.equals(Column.class)) {
                        continue;
                    }
                    List<DatabaseObject> objects = new ArrayList<DatabaseObject>(snapshot.get(type));
                    ListIterator<DatabaseObject> iterator = objects.listIterator();
                    while (iterator.hasNext()) {
                        Schema objectSchema = iterator.next().getSchema();
                        if (objectSchema == null || !objectSchema.equals(schema)) {
                            iterator.remove();
                        }
                    }
                    outputObjects(objects, type, catalogBuffer);
                }
                buffer.append(StringUtils.indent(catalogBuffer.toString(), 4));

            }

            return buffer.toString().replace("\r\n", "\n").replace("\r", "\n"); //standardize all newline chars

        } catch (Exception e) {
            throw new UnexpectedLiquibaseException(e);
        }

    }

    protected void outputObjects(List objects, Class type, StringBuilder catalogBuffer) {
        List<? extends DatabaseObject> databaseObjects = sort(objects);
        if (databaseObjects.size() > 0) {
            catalogBuffer.append(type.getName()).append(":\n");

            StringBuilder typeBuffer = new StringBuilder();
            for (DatabaseObject databaseObject : databaseObjects) {
                typeBuffer.append(databaseObject.getName()).append("\n");
                typeBuffer.append(StringUtils.indent(serialize(databaseObject, new HashSet<String>(), databaseObject.getName()), 4)).append("\n");
            }

            catalogBuffer.append(StringUtils.indent(typeBuffer.toString(), 4)).append("\n");
        }
    }

    private String serialize(final DatabaseObject databaseObject, Set<String> oldParentNames, String newParentName) {
        final Set<String> parentNames = new HashSet<String>(oldParentNames);
        parentNames.add(newParentName);

        StringBuilder buffer = new StringBuilder();

        final boolean expandContainedObjects = parentNames.size() <= getExpandDepth();

        final List<String> attributes = sort(databaseObject.getAttributes());
        for (String attribute : attributes) {
            if (attribute.equals("name")) {
                continue;
            }
            if (attribute.equals("schema")) {
                continue;
            }
            if (attribute.equals("catalog")) {
                continue;
            }
            Object value = databaseObject.getAttribute(attribute, Object.class);

            if (value instanceof Schema) {
                continue;
            }

            if (value instanceof DatabaseObject) {
                if (parentNames.contains(((DatabaseObject) value).getName())) {
                    value = null;
                } else if (expandContainedObjects) {
                    value = ((DatabaseObject) value).getName()+"\n"+StringUtils.indent(serialize((DatabaseObject) value, parentNames, databaseObject.getName()), 4);
                } else {
                    value = databaseObject.getSerializableFieldValue(attribute);
                }
            } else if (value instanceof Collection) {
                if (((Collection) value).size() == 0) {
                    value = null;
                } else {
                    if (((Collection) value).iterator().next() instanceof DatabaseObject && expandContainedObjects) {
                        value = StringUtils.join((Collection) value, "\n", new StringUtils.StringUtilsFormatter() {
                            @Override
                            public String toString(Object obj) {
                                if (obj instanceof DatabaseObject) {
                                    if (expandContainedObjects) {
                                        return ((DatabaseObject) obj).getName()+"\n"+StringUtils.indent(serialize(((DatabaseObject) obj), parentNames, databaseObject.getName()), 4);
                                    } else {
                                        return ((DatabaseObject) obj).getName();
                                    }
                                } else {
                                    return obj.toString();
                                }
                            }
                        });
                        value = "\n"+StringUtils.indent((String) value, 4);
                    } else {
                        value = databaseObject.getSerializableFieldValue(attribute);
                    }
                }
            } else {
                value = databaseObject.getSerializableFieldValue(attribute);
            }
            if (value != null) {
                buffer.append(attribute).append(": ").append(value).append("\n");
            }
        }

        return buffer.toString().replaceFirst("\n$", "");

    }

    public int getExpandDepth() {
        return expandDepth;
    }

    public void setExpandDepth(int expandDepth) {
        this.expandDepth = expandDepth;
    }

    protected void addDivider(StringBuilder buffer) {
        buffer.append("-----------------------------------------------------------------\n");
    }

    private List sort(Collection objects) {
        return sort(objects, new Comparator() {
            @Override
            public int compare(Object o1, Object o2) {
                if (o1 instanceof Comparable) {
                    return ((Comparable) o1).compareTo(o2);
                } else if (o1 instanceof Class) {
                    return ((Class) o1).getName().compareTo(((Class) o2).getName());
                } else {
                    throw new ClassCastException(o1.getClass().getName()+" cannot be cast to java.lang.Comparable or java.lang.Class");
                }
            }
        });
    }

    private <T> List<T> sort(Collection objects, Comparator<T> comparator) {
        List returnList = new ArrayList(objects);
        Collections.sort(returnList, comparator);

        return returnList;
    }

    @Override
    public void write(DatabaseSnapshot snapshot, OutputStream out) throws IOException {
        out.write(serialize(snapshot, true).getBytes());
    }
}
