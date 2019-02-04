/* Copyright (c) 2005-2019 - Blue River Systems Group, LLC - All Rights Reserved */
package io.vlingo.symbio.store.object.jdbc.jpa;

import java.util.Objects;

import io.vlingo.symbio.store.object.PersistentObject;

/**
 * Person
 *
 * <p>Copyright (c) 2005-2019 - Blue River Systems Group, LLC - All Rights Reserved</p>
 *
 * @author mas
 * @since Feb 1, 2019
 */
public class Person 
extends PersistentObject
{
    private static final long serialVersionUID = 1L;
    
    protected int age;
    protected long id;
    protected String name;
    
    public Person() {}
    
    public Person( long persistentId )
    {
        super( persistentId );
        this.id = persistentId;
    }
    
    public Person( long persistentId, int anAge, String aName )
    {
        this( persistentId );
        this.age = anAge;
        this.name = aName;
    }

    /* @see java.lang.Object#hashCode() */
    @Override
    public int hashCode()
    {
        return Objects.hash(id);
    }

    /* @see java.lang.Object#equals(java.lang.Object) */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (!(obj instanceof Person))
        {
            return false;
        }
        Person other = (Person) obj;
        return id == other.id;
    }

}
