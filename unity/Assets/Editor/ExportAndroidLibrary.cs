using UnityEditor;
using UnityEngine;
using System.IO;

/// <summary>
/// Exports the Unity project as an Android Library (UaaL) for embedding
/// in the Midnight Kicks Kotlin app.
///
/// Editor: menu "Midnight Kicks → Export Android Library"
///
/// CLI:
///   Unity -batchmode -nographics -quit \
///     -projectPath /path/to/midnight-kicks/unity \
///     -executeMethod ExportAndroidLibrary.Export
///
/// Output: unity/build/android-export/<projectName>/unityLibrary/
/// Copy unityLibrary/ into ../unityLibrary/ on the Kotlin side.
/// </summary>
public class ExportAndroidLibrary
{
    private static readonly string ExportPath = Path.Combine(
        Application.dataPath, "..", "build", "android-export"
    );

    [MenuItem("Midnight Kicks/Export Android Library")]
    public static void Export()
    {
        Debug.Log($"[UaaL Export] Starting Android library export to: {ExportPath}");

        // Clean previous export
        if (Directory.Exists(ExportPath))
        {
            Directory.Delete(ExportPath, recursive: true);
            Debug.Log("[UaaL Export] Cleaned previous export");
        }

        // Ensure Android build target
        if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.Android)
        {
            Debug.Log("[UaaL Export] Switching to Android platform...");
            EditorUserBuildSettings.SwitchActiveBuildTarget(
                BuildTargetGroup.Android, BuildTarget.Android
            );
        }

        // Configure for UaaL export
        EditorUserBuildSettings.exportAsGoogleAndroidProject = true;
        PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;

        // Build
        var options = new BuildPlayerOptions
        {
            scenes = new[] { "Assets/Scenes/SampleScene.unity" },
            locationPathName = ExportPath,
            target = BuildTarget.Android,
            options = BuildOptions.AcceptExternalModificationsToPlayer,
        };

        var report = BuildPipeline.BuildPlayer(options);

        if (report.summary.result == UnityEditor.Build.Reporting.BuildResult.Succeeded)
        {
            Debug.Log($"[UaaL Export] SUCCESS — exported to {ExportPath}");
            Debug.Log($"[UaaL Export] Total size: {report.summary.totalSize / 1024 / 1024}MB");
            Debug.Log($"[UaaL Export] Copy unityLibrary/ to your Kotlin project");
        }
        else
        {
            Debug.LogError($"[UaaL Export] FAILED: {report.summary.result}");
            foreach (var step in report.steps)
            {
                foreach (var msg in step.messages)
                {
                    if (msg.type == LogType.Error)
                        Debug.LogError($"  {msg.content}");
                }
            }
            // Exit with error code for CI
            EditorApplication.Exit(1);
        }
    }
}
