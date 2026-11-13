import Foundation

/// The C++ side of the viewfinder's look (Viewfinder/ViewfinderLook.mm, class SCAMViewfinderLook), reached by its Objective-C name:
/// the settings' film / LUT table (FilmChain), the lens filter or the film's diffusion with DiffusionModel's level weights for a
/// viewport, and the film's recipe for the EXIF (FilmSim.Settings.recipe).
enum ViewfinderNative {
    struct Look {
        /// FilmChain.current's key ("" no table) and the table's strength
        var key = ""
        var strength: Float = 0
        /// the table: size a side, float RGB with r fastest (FilmLut.rgb), its domain
        var size = 0
        var lut: Data?
        var domainMin = SIMD3<Float>(0, 0, 0)
        var domainMax = SIMD3<Float>(1, 1, 1)
        /// LiveLensFilter's filter number (0 none, 1..6 a diffusion), its strength and the level weights (the frame's, then mip 0..)
        var filter = 0
        var filterStrength: Float = 0
        var weights: [SIMD3<Float>] = []
        /// FilmSim.Settings.recipe of the film now ("" without one)
        var recipe = ""
    }

    private static let helper: NSObject? = (NSClassFromString("SCAMViewfinderLook") as? NSObject.Type)?.init()

    /// The look of `settings` (PostSettings.json()); `longPx` / `levels` the viewport's long side and its half-size pyramid's mip levels
    /// (0: no diffusion weights). Nil when the settings cannot be read.
    static func look(settings: String, longPx: Int = 0, levels: Int = 0, recipeOnly: Bool = false) -> Look? {
        guard let helper else { return nil }
        let selector = NSSelectorFromString("look:")
        guard helper.responds(to: selector) else { return nil }
        let request: NSDictionary = ["settings": settings, "long": longPx, "levels": levels, "recipeOnly": recipeOnly ? 1 : 0]
        guard let result = helper.perform(selector, with: request)?.takeUnretainedValue() as? NSDictionary else { return nil }
        var look = Look()
        look.key = result["key"] as? String ?? ""
        look.strength = (result["strength"] as? NSNumber)?.floatValue ?? 0
        look.size = (result["size"] as? NSNumber)?.intValue ?? 0
        look.lut = result["lut"] as? Data
        if let min = result["min"] as? [NSNumber], min.count == 3 { look.domainMin = SIMD3(min[0].floatValue, min[1].floatValue, min[2].floatValue) }
        if let max = result["max"] as? [NSNumber], max.count == 3 { look.domainMax = SIMD3(max[0].floatValue, max[1].floatValue, max[2].floatValue) }
        look.filter = (result["filter"] as? NSNumber)?.intValue ?? 0
        look.filterStrength = (result["filterStrength"] as? NSNumber)?.floatValue ?? 0
        if let flat = result["weights"] as? [NSNumber] {
            look.weights = stride(from: 0, to: flat.count - 2, by: 3).map { SIMD3(flat[$0].floatValue, flat[$0 + 1].floatValue, flat[$0 + 2].floatValue) }
        }
        look.recipe = result["recipe"] as? String ?? ""
        return look
    }

    /// The film's recipe for the photo's description ("" without a film, or when the settings cannot be read)
    static func filmRecipe() -> String {
        look(settings: PostSettings.json(), recipeOnly: true)?.recipe ?? ""
    }
}
