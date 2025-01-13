<div align="center">
<img src="https://i.imgur.com/KMwkYIK.jpeg" style="width: 20%;" alt="Icon">

## Bit's ORM (BORM)
[![Build](https://img.shields.io/github/actions/workflow/status/BitByLogics/Bits-ORM/.github/workflows/maven.yml?branch=master)](https://github.com/BitByLogics/Bits-ORM/actions)
![Issues](https://img.shields.io/github/issues-raw/BitByLogics/Bits-ORM)
[![Stars](https://img.shields.io/github/stars/BitByLogics/Bits-ORM)](https://github.com/BitByLogics/Bits-ORM/stargazers)
[![Chat)](https://img.shields.io/discord/1310486866272981002?logo=discord&logoColor=white)](https://discord.gg/syngw2UQUd)

An API for mapping Java objects to SQL, enabling seamless saving and loading of data to and from tables.
</div>

## Features
* Simple setup, only requires 2 classes to use
* Foreign table support
* Easily handle complex objects with Field Processors
* Automatic column data type inference
* SQL and SQLite support natively
* Seamless data synchronization using Redis (Bit's RPS)

## IMPORTANT
In order for BORM to work, you must add the parameters flag when compiling!

### For Maven
```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
                    <compilerArgs>
                        <arg>-parameters</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
```

## Maven Dependency and repository
To use BORM, you simply need to shade the API into your project!

```xml
		<repository>
		    <id>jitpack.io</id>
		    <url>https://jitpack.io</url>
		</repository>
```

```xml
	<dependency>
	    <groupId>com.github.BitByLogics</groupId>
	    <artifactId>Bits-ORM</artifactId>
	    <version>1.0.0</version>
	</dependency>
```

## Or, for Gradle
```kotlin
repsitories {
    maven {
        url = uri("https://jitpack.io")
    }
}
```

```kotlin
dependencies {
    compileOnly("com.github.BitByLogics:Bits-ORM:1.0.0")
}
```

## Usage
### Using `BORM` is simple! Below is an example usage of the API and its capabilities.

BormAPI has three constructors:

* BormAPI(String address, String database, String port, String username, String password)
* BormAPI(HikariConfig config)
* BormAPI(File databaseFile)

In these examples we'll be using the File constructor, which uses SQLite.

### First, initialize your instance of the BormAPI. This class is essential and will handle all BormTables
as well as executing the actual SQL statements.

```java
import net.bitbylogic.orm.BormAPI;

public class Example {

    private final BormAPI bormAPI;

    public Example() {
        this.bormAPI = new BormAPI(new File("database.sql"));
    }

    public static void main(String[] args) {
        new Example();
    }

}
```

That's it! The BormAPI class is now initialized, we can now utilize it to create our table and data object!

### Next, we'll create our Java object that will map to the SQL table.

To create an object that will be mapped to SQL, first extend the BormObject class. Next, create fields that you want to be stored in the table.
The order of the fields determines the order of the columns in the table. For example, id will appear before creationDate.

The column annotation is your main entrypoint to defining that a field is associated with a column. By annotating a field with it, when the class
is parsed by the BormTable it will identify relevant information from that field and create a database column. By default, the columns name will
be the fields name exactly, so creationDate will make the column creationDate! However, you can modify this by specifying a new name in the Column
annotation. Any fields missing the Column annotation will be ignored by the table.

The annotation has various attributes that can be defined which changes how the column will be created. You can find a list of Column attributes after this example.

**IMPORTANT** `In order for BORM to work, the object MUST have an empty constructor and a constructor with all fields defined with the column annotation!`

```java
import net.bitbylogic.orm.annotation.Column;
import net.bitbylogic.orm.data.BormObject;

import java.util.UUID;

public class ExampleUser extends BormObject {

    @Column(primaryKey = true)
    private UUID id;

    @Column
    private long creationDate;

    public ExampleUser(UUID id) {
        this.id = id;
        this.creationDate = System.currentTimeMillis();
    }

    public ExampleUser(UUID id, long creationDate) {
        this.id = id;
        this.creationDate = creationDate;
    }

}
```

### Next, create your BormTable and register it via the BormAPI

The constructor of BormTable requires 4 objects:
* BormAPI - An instance of the BormAPI to allow the table to execute statements
* BormObject Class Reference - Used to parse and load field information
* Table Name - The name of the sql table, can be anything
* Load Data Boolean - Whether to load all data from the table into the mapped Java object

```java
import net.bitbylogic.orm.BormAPI;
import net.bitbylogic.orm.data.BormTable;

public class ExampleTable extends BormTable<ExampleUser> {

    public ExampleTable(BormAPI bormAPI) {
        super(bormAPI, ExampleUser.class, "example_users", true);
    }

}
```

```java
import net.bitbylogic.orm.BormAPI;

public class Example {

    private final BormAPI bormAPI;

    private ExampleTable exampleTable;

    public Example() {
        this.bormAPI = new BormAPI(new File("database.sql"));

        bormAPI.registerTable(ExampleTable.class, exampleTable -> this.exampleTable = exampleTable);
    }

    public static void main(String[] args) {
        new Example();
    }

}
```

### Adding/Removing data from the table

```java
import net.bitbylogic.orm.BormAPI;

import java.util.UUID;

public class Example {

    private final BormAPI bormAPI;

    private ExampleTable exampleTable;

    public Example() {
        this.bormAPI = new BormAPI(new File("database.sql"));

        bormAPI.registerTable(ExampleTable.class, exampleTable -> {
            this.exampleTable = exampleTable;

            ExampleUser exampleUser = new ExampleUser(UUID.randomUUID());
            exampleTable.add(exampleUser);

            // Once a mapped object is added to the table, convenient methods can be used to save/delete the object
            exampleUser.save();
            exampleUser.delete();
        });
    }

    public static void main(String[] args) {
        new Example();
    }

}
```

# Column Annotation Attributes

| Attribute Name | Attribute Object | Default Value | Description                                                                                                                                                        |
|----------------|------------------|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| dataType       | String           | ""            | This allows you to define the data type of the field, for example `VARCHAR(36)`. If left empty, it will be inferred based on the fields type.                      |
| name           | String           | Field's Name  | This is the name that the column will be called, by default will be the fields name.                                                                               |
| allowNull      | Boolean          | false         | Determines whether or not the column can be `NULL` in the table.                                                                                                   |
| autoIncrement  | Boolean          | false         | Used for int/Integer fields, will set the field as a unique number generated by SQL.                                                                               |
| primaryKey     | Boolean          | false         | Marks this field as the objects primary key, this is what's used to retrieve the object from the table. Only one field can be marked as primaryKey per BormObject. |
| updateOnSave   | Boolean          | true          | Marks this field for being saved when the object is saved to the database.                                                                                         |
| foreignTable   | String           | ""            | This is the foreign table name of fields BormObject. Used to retrieve the BormTable and thus fetch the BormObject from it.                                         |
| cascadeDelete  | Boolean          | false         | Determines whether the foreign object linked to this field should be deleted from its table when this object is deleted.                                           |

# Registering Field Processors For Complex Objects

For complex objects, you can create a FieldProcessor that allows you to process the object into an object and load it from an object!

Let's say you have this ComplexObject and want to add it as a column in the table.

```java
public class ComplexObject {

    private final String name;
    private final int favoriteNumber;
    private final String favoriteColor;

    public ComplexObject(String name, int favoriteNumber, String favoriteColor) {
        this.name = name;
        this.favoriteNumber = favoriteNumber;
        this.favoriteColor = favoriteColor;
    }

    public String getName() {
        return name;
    }

    public int getFavoriteNumber() {
        return favoriteNumber;
    }

    public String getFavoriteColor() {
        return favoriteColor;
    }

}
```

This object cannot be automatically stored in the table and has to be processed somehow, that's where the FieldProcessor interface comes in!
You can create a processor by implementing the FieldProcessor interface and subsequent methods.

```java
public class ExampleFP implements FieldProcessor<ComplexObject> {

    @Override
    public Object processTo(ComplexObject object) {
        return String.format("%s:%s:%s", object.getName(), object.getFavoriteNumber(), object.getFavoriteColor());
    }

    @Override
    public ComplexObject processFrom(Object object) {
        String[] data = ((String) object).split(":");
        return new ComplexObject(data[0], Integer.parseInt(data[1]), data[2]);
    }

}
```

In this example, we just parse the object to a string and back from a string! You then need to register your processor to the BormAPI instance.

```java
import net.bitbylogic.orm.BormAPI;

public class Example {

    private final BormAPI bormAPI;

    private ExampleTable exampleTable;

    public Example() {
        this.bormAPI = new BormAPI(new File("database.sql"));

        bormAPI.registerTable(ExampleTable.class, exampleTable -> {
            this.exampleTable = exampleTable;

            ExampleUser exampleUser = new ExampleUser(UUID.randomUUID());
            exampleTable.add(exampleUser);

            // Once a mapped object is added to the table, convenient methods can be used to save/delete the object
            exampleUser.save();
            exampleUser.delete();

            bormAPI.registerFieldProcessor(new TypeToken<>() {}, new ExampleFP());
        });
    }

    public static void main(String[] args) {
        new Example();
    }

}
```

Now any fields that are marked with the Column annotation and are of the type ComplexObject will be processed with the ExampleFP field processor!
