// Sumer Studio on-device Dart kernel compiler driver.
//
// Runs the official Dart Common Front End (CFE) to compile a Dart entrypoint
// into a Flutter-loadable kernel blob:
//
//   dart kernel_driver.dart \
//     --target=flutter \
//     --platform=<toolchain>/flutter_patched_sdk/platform_strong.dill \
//     --libraries-json=<toolchain>/flutter_patched_sdk/libraries.json \
//     --packages=<user package_config.json> \
//     --omit-platform \
//     --output=<assets>/flutter_assets/kernel_blob.bin \
//     <project>/lib/main.dart
//
// Exit code 0  = kernel written successfully.
// Exit code 1  = compilation errors (diagnostics printed to stderr);
//                no kernel is written in that case.

import 'dart:io' show exit, stderr;

import 'package:_fe_analyzer_shared/src/messages/severity.dart'
    show CfeSeverity;
import 'package:front_end/src/api_prototype/compiler_options.dart'
    show CfeDiagnosticMessage;
import 'package:front_end/src/base/compiler_context.dart' show CompilerContext;
import 'package:front_end/src/kernel/utils.dart' show writeComponentToFile;
import 'package:front_end/src/kernel_generator_impl.dart'
    show generateKernelInternal;
import '../../front_end/tool/command_line.dart'
    as cmd_line show runProtectedFromAbort, withGlobalOptions;
import 'package:kernel/kernel.dart' show Component, Library, Source;
import 'package:vm/modular/target/install.dart' as targets
    show installAdditionalTargets;

Future<void> main(List<String> arguments) async {
  targets.installAdditionalTargets();
  await cmd_line.runProtectedFromAbort(() async {
    return await cmd_line.withGlobalOptions("compile", arguments, true, (
      CompilerContext c,
      _,
    ) async {
      if (c.options.verbose) {
        print("Sumer kernel driver: compiling ${arguments.join(' ')}");
      }

      // Collect error-severity diagnostics so we can fail loudly: the CFE
      // alone happily writes an (unusable) kernel even when errors occurred.
      final List<String> errorLines = <String>[];
      void Function(CfeDiagnosticMessage)? original =
          c.options.rawOptionsForTesting.onDiagnostic;
      c.options.rawOptionsForTesting.onDiagnostic = (CfeDiagnosticMessage m) {
        if (m.severity == CfeSeverity.error) {
          errorLines.addAll(m.plainTextFormatted);
        }
        if (original != null) {
          original(m);
        } else {
          c.options.defaultDiagnosticMessageHandler(m);
        }
      };

      var result = await generateKernelInternal(c);
      Component component = result.component!;

      if (errorLines.isNotEmpty) {
        stderr.writeln("Sumer kernel driver: ${errorLines.length} error(s):");
        for (final line in errorLines) {
          stderr.writeln(line);
        }
        exit(1);
      }

      if (c.options.omitPlatform) {
        component.computeCanonicalNames();
        Component userCode = new Component(
          nameRoot: component.root,
          uriToSource: new Map<Uri, Source>.from(component.uriToSource),
        );
        userCode.setMainMethodAndMode(component.mainMethodName, true);
        for (Library library in component.libraries) {
          if (!library.importUri.isScheme("dart")) {
            userCode.libraries.add(library);
          }
        }
        component = userCode;
      }
      Uri uri = c.options.output!;
      await writeComponentToFile(component, uri);
      c.options.ticker.logMs("Sumer kernel driver: wrote ${uri.toFilePath()}");
    });
  });
}
