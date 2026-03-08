package com.sanshain.maven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.lang.reflect.Method;

public class ProvideMojoTest {

    @Test
    public void testGetGitBranch() throws Exception {
        ProvideMojo mojo = new ProvideMojo();
        
        // Use reflection to set baseDir
        java.lang.reflect.Field baseDirField = ProvideMojo.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        baseDirField.set(mojo, new File(".")); // Current dir is also part of the git repo
        
        Method getGitBranchMethod = ProvideMojo.class.getDeclaredMethod("getGitBranch");
        getGitBranchMethod.setAccessible(true);
        
        String branch = (String) getGitBranchMethod.invoke(mojo);
        
        assertNotNull(branch);
        System.out.println("Detected branch: " + branch);
    }
}
