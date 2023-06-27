/*
 *
 *  Copyright 2011 Cédric Champeau
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package org.objectweb.asm.idea.plugin.action;

import com.intellij.ide.util.JavaAnonymousClassesHelper;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorImpl;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.idea.plugin.common.Constants;
import org.objectweb.asm.idea.plugin.view.BytecodeASMified;
import org.objectweb.asm.idea.plugin.view.BytecodeOutline;
import org.objectweb.asm.idea.plugin.view.GroovifiedView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Given a java file (or any file which generates classes), tries to locate a .class file. If the compilation state is
 * not up to date, performs an automatic compilation of the class. If the .class file can be located, generates bytecode
 * instructions for the class and ASMified code, and displays them into a tool window.
 *
 * @author Cédric Champeau
 * @author Kamiel Ahmadpour
 */
public class ShowBytecodeViewerAction extends AnAction {

    @Override
    public void update(final AnActionEvent e) {
        final VirtualFile virtualFile = e.getData(PlatformDataKeys.VIRTUAL_FILE);
        final Project project = e.getData(PlatformDataKeys.PROJECT);
        final PsiElement psiClass = e.getData(LangDataKeys.PSI_ELEMENT);
        final Presentation presentation = e.getPresentation();
        if (project == null || virtualFile == null) {
            presentation.setEnabled(false);
            return;
        }
        final PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
        presentation.setEnabled(psiFile instanceof PsiClassOwner);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        final VirtualFile virtualFile = e.getData(PlatformDataKeys.VIRTUAL_FILE);
        final Project project = e.getData(PlatformDataKeys.PROJECT);

        if (project == null || virtualFile == null) return;
        final PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
        if (psiFile instanceof PsiClassOwner) {
            // get current psi element
            final PsiElement psiElement = e.getData(PlatformDataKeys.PSI_ELEMENT);
            // get class from psi element
            final PsiClass psiClass = psiElement instanceof PsiClass ? (PsiClass) psiElement : null; // TODO: get proper class for current location in file

            if ("class".equals(virtualFile.getExtension())) {
                updateToolWindowContents(project, virtualFile, (PsiClassOwner) psiFile, psiClass);
            } else if (!virtualFile.isInLocalFileSystem() && !virtualFile.isWritable()) {
                // probably a source file in a library
                final PsiClass[] psiClasses = ((PsiClassOwner) psiFile).getClasses();
                if (psiClasses.length > 0) {
                    updateToolWindowContents(project, psiClasses[0].getOriginalElement().getContainingFile().getVirtualFile(), (PsiClassOwner) psiFile, psiClass);
                }
            } else {
                Module module = ModuleUtil.findModuleForPsiElement(psiFile);
                if(module == null) return;
                Application application = ApplicationManager.getApplication();
                application.runWriteAction(() -> FileDocumentManager.getInstance().saveAllDocuments());
                application.executeOnPooledThread(() -> {
                    final CompilerModuleExtension cme = CompilerModuleExtension.getInstance(module);
                    if(cme == null) return;
                    final CompilerManager compilerManager = CompilerManager.getInstance(project);
                    final VirtualFile[] files = {virtualFile};
                    final CompileScope compileScope = compilerManager.createFilesCompileScope(files);
                    final VirtualFile[] result = {null};
                    final VirtualFile[] outputDirectories = cme.getOutputRoots(true);
                    final Semaphore semaphore = new Semaphore(1);
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e1) {
                        result[0] = null;
                    }
                    if (compilerManager.isUpToDate(compileScope)) {
                        application.invokeLater(() -> {
                            result[0] = findClassFile(outputDirectories, psiFile);
                            semaphore.release();
                        });
                    } else {
                        application.invokeLater(() -> compilerManager.compile(files, (aborted, errors, warnings, compileContext) -> {
                            if (errors == 0) {
                                VirtualFile[] outputDirectories1 = cme.getOutputRoots(true);
                                result[0] = findClassFile(outputDirectories1, psiFile);
                            }
                            semaphore.release();
                        }));
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e1) {
                            result[0] = null;
                        }
                    }
                    application.invokeLater(() -> updateToolWindowContents(project, result[0], (PsiClassOwner) psiFile, psiClass));
                });
            }
        }
    }

    private VirtualFile findClassFile(final VirtualFile[] outputDirectories, final PsiFile psiFile) {
        return ApplicationManager.getApplication().runReadAction(new Computable<>() {

            @Override
            public VirtualFile compute() {
                if (outputDirectories != null && psiFile instanceof PsiClassOwner psiJavaFile) {
                    FileEditor editor = FileEditorManager.getInstance(psiFile.getProject()).getSelectedEditor(psiFile.getVirtualFile());
                    int caretOffset = (editor == null) ? -1 : ((PsiAwareTextEditorImpl) editor).getEditor().getCaretModel().getOffset();
                    if (caretOffset >= 0) {
                        PsiElement psiElement = psiFile.findElementAt(caretOffset);
                        PsiClass classAtCaret = findClassAtCaret(psiElement);
                        if (classAtCaret != null) {
                            return getClassFile(classAtCaret);
                        }
                    }
                    for (PsiClass psiClass : psiJavaFile.getClasses()) {
                        final VirtualFile file = getClassFile(psiClass);
                        if (file != null) {
                            return file;
                        }
                    }
                }
                return null;
            }

            private VirtualFile getClassFile(PsiClass psiClass) {
                String className = psiClass.getQualifiedName();
                if (className == null) {
                    if (psiClass instanceof PsiAnonymousClass) {
                        PsiClass parentClass = PsiTreeUtil.getParentOfType(psiClass, PsiClass.class);
                        if (parentClass != null) {
                            className = parentClass.getQualifiedName() + JavaAnonymousClassesHelper.getName((PsiAnonymousClass) psiClass);
                        }
                    } else {
                        PsiClass parentOfType = PsiTreeUtil.getParentOfType(psiClass, PsiClass.class);
                        if (parentOfType != null) {
                            className = parentOfType.getQualifiedName();
                        }
                    }
                }
                if (className != null) {
                    StringBuilder sb = new StringBuilder(className);
                    while (psiClass.getContainingClass() != null) {
                        sb.setCharAt(sb.lastIndexOf("."), '$');
                        psiClass = psiClass.getContainingClass();
                    }
                    String classFileName = sb.toString().replace('.', '/') + ".class";
                    for (VirtualFile outputDirectory : outputDirectories) {
                        final VirtualFile file = outputDirectory.findFileByRelativePath(classFileName);
                        if (file != null && file.exists()) {
                            return file;
                        }
                    }
                }
                return null;
            }

            private PsiClass findClassAtCaret(PsiElement psiElement) {
                while (psiElement != null) {
                    if (psiElement instanceof PsiClass) {
                        return (PsiClass) psiElement;
                    }
                    psiElement = psiElement.getParent();
                    findClassAtCaret(psiElement);
                }
                return null;
            }
        });
    }

    /**
     * Reads the .class file, processes it through the ASM TraceVisitor and ASMifier to update the contents of the two
     * tabs of the tool window.
     *
     * @param project the project instance
     * @param file    the class file
     */
    private void updateToolWindowContents(final Project project, final VirtualFile file, final PsiClassOwner classOwner, final PsiClass psiClass) {
        ApplicationManager.getApplication().runWriteAction(() -> {
            BytecodeOutline bytecodeOutline = BytecodeOutline.getInstance(project);
            BytecodeASMified asmifiedView = BytecodeASMified.getInstance(project);
            GroovifiedView groovifiedView = GroovifiedView.getInstance(project);
            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);

            if (file == null) {
                bytecodeOutline.setCodeFiles(null);
                bytecodeOutline.loadFile(null);
                asmifiedView.setCodeFiles(null);
                asmifiedView.loadFile(null);
                groovifiedView.setCodeFiles(null);
                groovifiedView.loadFile(null);
                ToolWindow toolWindow = toolWindowManager.getToolWindow(Constants.PLUGIN_WINDOW_NAME);
                if(toolWindow != null) toolWindow.activate(null);
                return;
            }

            // get the contained classes
            List<VirtualFile> containedClasses = new ArrayList<>();
            for (VirtualFile f : file.getParent().getChildren()) {
                if (f.getName().startsWith(file.getName().substring(0, file.getName().indexOf(".")) + "$") && f.getName().endsWith(".class")) {
                    containedClasses.add(f);
                }
            }
            containedClasses.add(file);

            // to map
            Map<String, VirtualFile> map = new HashMap<>();
            for (VirtualFile virtualFile : containedClasses) {
                map.put(virtualFile.getName().substring(0, virtualFile.getName().length() - 6), virtualFile);
            }

            // get file in map for current
            String name = psiClass != null ? psiClass.getName() : file.getName().substring(0, file.getName().length() - 6);
            PsiClass contain = psiClass;
            if (contain != null) {
                while ((contain = contain.getContainingClass()) != null) {
                    name = contain.getName() + "$" + name;
                }
            }
            VirtualFile current = map.get(name);
            if (current == null) {
                name = file.getName().substring(0, file.getName().length() - 6);
            }

            bytecodeOutline.setCodeFiles(map);
            bytecodeOutline.loadFile(name);
            asmifiedView.setCodeFiles(map);
            asmifiedView.loadFile(name);
            groovifiedView.setCodeFiles(map);
            groovifiedView.loadFile(name);

            // activate the tool window
            ToolWindow toolWindow = toolWindowManager.getToolWindow(Constants.PLUGIN_WINDOW_NAME);
            if(toolWindow != null) toolWindow.activate(null);
        });
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}